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

				<div v-if="running && progress" class="progress">
					<v-progress-circular indeterminate small />
					<span>
						Übersetze {{ Math.min(progress.done + 1, progress.total) }}/{{ progress.total }}<template v-if="progress.current"> — {{ progress.current }}</template>
					</span>
				</div>
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
		const fields = ref([]);
		const selected = ref([]);
		const overwrite = ref(false);
		const progress = ref(null); // { done, total, current } while running

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
				fields.value = data.fields || [];
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
			// One request per field/language pair: the gateway translates languages
			// sequentially, so a whole field × several languages in one request can
			// still outlive the reverse-proxy read timeout (→ 502) on long fields.
			// One pair keeps every request to a single gateway call.
			const fieldList = fields.value.length ? fields.value : [null];
			const pairs = [];
			for (const field of fieldList) {
				for (const lang of selected.value) pairs.push({ field, lang });
			}
			progress.value = { done: 0, total: pairs.length, current: null };
			const filledLangs = new Set();
			const errors = [];
			try {
				for (const { field, lang } of pairs) {
					progress.value = { ...progress.value, current: field ? `${field} → ${lang}` : lang };
					try {
						const { data } = await api.post(
							`/llm-translate/${props.collection}/${encodeURIComponent(props.primaryKey)}`,
							{
								languages: [lang],
								overwrite: overwrite.value,
								...(field ? { fields: [field] } : {}),
							},
						);
						for (const l of Object.keys(data.filled || {})) filledLangs.add(l);
					} catch (err) {
						errors.push(`${field ? field + ' → ' : ''}${lang}: ${err?.response?.data?.error || err.message}`);
					}
					progress.value = { ...progress.value, done: progress.value.done + 1 };
				}

				if (errors.length) {
					error.value = errors.join('; ');
					notifications.add({
						title: 'Einige Felder fehlgeschlagen',
						text: error.value,
						type: 'error',
					});
					// Keep the form (no reload) so the error notice stays visible.
				} else {
					notifications.add({
						title: filledLangs.size
							? `Übersetzt: ${[...filledLangs].join(', ')}`
							: 'Keine Felder zu übersetzen',
						type: 'success',
					});
					// Reload so the freshly written translation rows appear in the form.
					setTimeout(() => window.location.reload(), 800);
				}
			} finally {
				running.value = false;
				progress.value = null;
			}
		}

		watch(() => props.primaryKey, loadInfo);
		onMounted(loadInfo);

		return { isNew, loadingInfo, running, error, hasGerman, targets, selected, overwrite, progress, translate };
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
.progress {
	display: flex;
	align-items: center;
	gap: 8px;
	margin-top: 12px;
	color: var(--theme--foreground-subdued);
	font-size: 0.9em;
}
</style>
