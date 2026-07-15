<template>
	<div class="llm-translate">
		<v-notice v-if="isNew" type="info">
			Bitte speichere den Eintrag zuerst, dann kann übersetzt werden.
		</v-notice>

		<template v-else>
			<v-notice v-if="error" type="danger">{{ error }}</v-notice>

			<v-notice v-else-if="!loadingInfo && !hasGerman" type="warning">
				Keine deutschen Inhalte vorhanden — es gibt nichts zu übersetzen.
			</v-notice>

			<template v-else>
				<div class="hint">
					Übersetzt die zuletzt <strong>gespeicherten</strong> deutschen Inhalte in die
					ausgewählten Sprachen. Ungespeicherte Änderungen bitte vorher speichern.
				</div>

				<div v-if="loadingInfo" class="loading"><v-progress-circular indeterminate small /> Lade Sprachen…</div>

				<div v-else class="langs">
					<v-checkbox
						v-for="t in targets"
						:key="t.code"
						v-model="selected"
						:value="t.code"
						:label="`${t.label}${t.empty ? ' (leer)' : ''}`"
						:disabled="running"
						block
					/>
				</div>

				<v-checkbox
					v-model="overwrite"
					label="Vorhandene Übersetzungen überschreiben"
					:disabled="running"
					class="overwrite"
				/>

				<v-button
					:loading="running"
					:disabled="running || loadingInfo || selected.length === 0"
					@click="translate"
				>
					<v-icon name="translate" left />
					Übersetzen
				</v-button>
			</template>
		</template>
	</div>
</template>

<script>
import { defineComponent, ref, computed, watch, onMounted } from 'vue';
import { useApi, useStores } from '@directus/extensions-sdk';

export default defineComponent({
	props: {
		collection: { type: String, required: true },
		primaryKey: { type: [String, Number], default: null },
	},
	setup(props) {
		const api = useApi();
		const { useNotificationsStore } = useStores();
		const notifications = useNotificationsStore();

		const isNew = computed(() => props.primaryKey === '+' || props.primaryKey == null);

		const loadingInfo = ref(false);
		const running = ref(false);
		const error = ref(null);
		const hasGerman = ref(false);
		const targets = ref([]);
		const selected = ref([]);
		const overwrite = ref(false);

		async function loadInfo() {
			if (isNew.value) return;
			loadingInfo.value = true;
			error.value = null;
			try {
				const { data } = await api.get(
					`/llm-translate/${props.collection}/${encodeURIComponent(props.primaryKey)}/info`,
				);
				hasGerman.value = data.hasGerman;
				targets.value = data.targets;
				// Pre-check languages whose translation row is entirely empty.
				selected.value = data.targets.filter((t) => t.empty).map((t) => t.code);
			} catch (err) {
				error.value = err?.response?.data?.error || err.message || 'Fehler beim Laden';
			} finally {
				loadingInfo.value = false;
			}
		}

		async function translate() {
			running.value = true;
			error.value = null;
			try {
				const { data } = await api.post(
					`/llm-translate/${props.collection}/${encodeURIComponent(props.primaryKey)}`,
					{ languages: selected.value, overwrite: overwrite.value },
				);
				const langs = Object.keys(data.filled || {});
				notifications.add({
					title: langs.length
						? `Übersetzt: ${langs.join(', ')}`
						: 'Keine Felder zu übersetzen',
					type: 'success',
				});
				// Reload so the freshly written translation rows appear in the form.
				setTimeout(() => window.location.reload(), 800);
			} catch (err) {
				error.value = err?.response?.data?.error || err.message || 'Übersetzung fehlgeschlagen';
				notifications.add({ title: 'Übersetzung fehlgeschlagen', text: error.value, type: 'error' });
			} finally {
				running.value = false;
			}
		}

		watch(() => props.primaryKey, loadInfo);
		onMounted(loadInfo);

		return { isNew, loadingInfo, running, error, hasGerman, targets, selected, overwrite, translate };
	},
});
</script>

<style scoped>
.llm-translate {
	max-width: 640px;
}
.hint {
	color: var(--theme--foreground-subdued);
	font-size: 0.9em;
	margin-bottom: 12px;
}
.loading {
	display: flex;
	align-items: center;
	gap: 8px;
	color: var(--theme--foreground-subdued);
}
.langs {
	margin-bottom: 12px;
}
.overwrite {
	margin-bottom: 16px;
}
</style>
