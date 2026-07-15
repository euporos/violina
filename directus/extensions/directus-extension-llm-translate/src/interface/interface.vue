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
					<span v-if="progress.total > 0">
						Übersetze {{ Math.min(progress.done + 1, progress.total) }}/{{ progress.total }}<template v-if="progress.current"> — {{ progress.current }}</template>
					</span>
					<span v-else>Starte…</span>
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
			progress.value = { done: 0, total: 0, current: null };
			const base = `/llm-translate/${props.collection}/${encodeURIComponent(props.primaryKey)}`;
			const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
			try {
				// Start the job; the server does the work and we poll for progress, so
				// no request is held open and no proxy timeout can bite.
				const { data: started } = await api.post(base, {
					languages: selected.value,
					overwrite: overwrite.value,
				});
				const jobId = started.jobId;

				let job = null;
				let misses = 0;
				while (running.value) {
					await sleep(1500);
					try {
						const { data } = await api.get(`${base}/job/${encodeURIComponent(jobId)}`);
						job = data;
						misses = 0;
						progress.value = { done: job.done, total: job.total, current: job.current };
						if (job.status !== 'running') break;
					} catch (err) {
						// A job that 404s vanished (server restarted) — completed fields are
						// saved; re-running resumes via skip-already-translated. Tolerate a
						// few transient poll blips before giving up.
						if (err?.response?.status === 404) {
							throw new Error('Job nicht mehr verfügbar (Server neu gestartet?) — bereits übersetzte Felder sind gespeichert, bitte erneut ausführen.');
						}
						if (++misses > 3) throw err;
					}
				}
				if (!job) return;

				if (!job.hasGerman) {
					notifications.add({ title: 'Keine deutschen Inhalte zum Übersetzen', type: 'warning' });
					return;
				}
				if (job.status === 'error') {
					// Some fields may have succeeded; keep the notice visible (no reload)
					// so the user sees what failed (e.g. a field over the length cap).
					error.value = (job.errors || []).join('; ') || 'Übersetzung fehlgeschlagen';
					notifications.add({ title: 'Übersetzung teils fehlgeschlagen', text: error.value, type: 'error' });
				} else {
					const langs = Object.keys(job.filled || {});
					notifications.add({
						title: langs.length ? `Übersetzt: ${langs.join(', ')}` : 'Nichts zu übersetzen (alles aktuell)',
						type: 'success',
					});
					// Reload so the freshly written translation rows appear in the form.
					if (langs.length) setTimeout(() => window.location.reload(), 800);
				}
			} catch (err) {
				error.value = err?.response?.data?.error || err.message || 'Übersetzung fehlgeschlagen';
				notifications.add({ title: 'Übersetzung fehlgeschlagen', text: error.value, type: 'error' });
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
