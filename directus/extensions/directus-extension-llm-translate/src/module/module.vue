<template>
	<private-view title="Übersetzungen">
		<template #navigation>
			<div class="nav-pad">
				<v-notice type="info">
					Füllt fehlende Übersetzungen site-weit aus dem Deutschen. Vorhandene Übersetzungen
					bleiben unverändert.
				</v-notice>
			</div>
		</template>

		<div class="llm-module">
			<v-notice v-if="error" type="danger">{{ error }}</v-notice>

			<p class="intro">
				Für jede übersetzte Collection werden alle Einträge durchlaufen; nur leere
				Übersetzungsfelder werden gefüllt (kein Überschreiben). Das kann je nach Menge einige
				Minuten dauern.
			</p>

			<v-button :loading="running" :disabled="running || collections.length === 0" @click="fillAll">
				<v-icon name="translate" left />
				Fehlende Übersetzungen füllen
			</v-button>

			<div v-if="progress.length" class="progress">
				<div v-for="row in progress" :key="row.collection" class="row">
					<v-icon :name="row.icon" small :class="row.state" />
					<span class="name">{{ row.collection }}</span>
					<span class="detail">{{ row.detail }}</span>
				</div>
			</div>
		</div>
	</private-view>
</template>

<script>
import { defineComponent, ref, onMounted } from 'vue';
import { useApi } from '@directus/extensions-sdk';

export default defineComponent({
	setup() {
		const api = useApi();
		const collections = ref([]);
		const running = ref(false);
		const error = ref(null);
		const progress = ref([]);

		async function loadCollections() {
			try {
				const { data } = await api.get('/llm-translate/collections');
				collections.value = data.collections;
			} catch (err) {
				error.value = err?.response?.data?.error || err.message;
			}
		}

		async function fillAll() {
			running.value = true;
			error.value = null;
			progress.value = collections.value.map((c) => ({
				collection: c,
				state: 'pending',
				icon: 'hourglass_empty',
				detail: 'wartet…',
			}));

			// Drive one request per collection so a single call never runs long
			// enough to hit the gateway's proxy timeout.
			for (const row of progress.value) {
				row.state = 'running';
				row.icon = 'sync';
				row.detail = 'übersetzt…';
				try {
					const { data } = await api.post(
						`/llm-translate/all?collection=${encodeURIComponent(row.collection)}`,
					);
					const s = (data.summary && data.summary[row.collection]) || { items: 0, fieldsFilled: 0 };
					row.state = 'done';
					row.icon = 'check_circle';
					row.detail = `${s.items} Einträge, ${s.fieldsFilled} Felder gefüllt`;
				} catch (err) {
					row.state = 'error';
					row.icon = 'error';
					row.detail = err?.response?.data?.error || err.message || 'Fehler';
				}
			}

			running.value = false;
		}

		onMounted(loadCollections);

		return { collections, running, error, progress, fillAll };
	},
});
</script>

<style scoped>
.llm-module {
	padding: var(--content-padding);
	max-width: 720px;
}
.intro {
	color: var(--theme--foreground-subdued);
	margin-bottom: 20px;
}
.nav-pad {
	padding: 12px;
}
.progress {
	margin-top: 24px;
}
.progress .row {
	display: flex;
	align-items: center;
	gap: 10px;
	padding: 6px 0;
	border-bottom: 1px solid var(--theme--border-color-subdued);
}
.progress .name {
	font-family: var(--theme--fonts--monospace--font-family);
	min-width: 180px;
}
.progress .detail {
	color: var(--theme--foreground-subdued);
}
.progress .running {
	color: var(--theme--primary);
}
.progress .done {
	color: var(--theme--success);
}
.progress .error {
	color: var(--theme--danger);
}
</style>
