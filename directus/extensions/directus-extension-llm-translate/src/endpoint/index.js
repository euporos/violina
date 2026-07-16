// llm-translate — server endpoint that drives the Babashka LLM gateway
// (https://llm.olivermotz.net) to translate a translated item's fields from the
// German reference row into other languages.
//
// It holds the gateway API key (never shipped to the browser) and does all the
// work: introspect the <collection>_translations shape, read the German row,
// call the gateway once per field, and upsert the target-language rows.
//
// Routes (mounted under /llm-translate):
//   GET  /collections                 → { source, targets, collections }
//   GET  /:collection/:pk/info        → { hasGerman, fields, targets:[{code,label,empty}] }
//   POST /:collection/:pk             → translate one item  { languages, overwrite }
//   POST /all?collection=<c>          → fill missing translations for a whole collection
//
// Config (env, set by the Directus .env / systemd unit):
//   LLM_GATEWAY_URL      base URL of the gateway   (e.g. https://llm.olivermotz.net)
//   LLM_GATEWAY_API_KEY  shared bearer secret

const SOURCE_LANG = 'de';

// The gateway runs gemma3:12b on CPU at roughly ~30 characters/second. vps-3
// caps a single gateway call at 30 min (nginx proxy_read_timeout + the gateway's
// own Ollama HTTP timeout), so keep a unit's source well under that (~30k chars
// ≈ ~17 min expected, ~21 min worst-observed) for comfortable margin; a longer
// source field is skipped with an error rather than risking that ceiling. Tune
// if the model/hardware changes.
const MAX_CHARS_PER_UNIT = 30000;

// Hard server-side ceiling for one translate job (a whole item = several
// field×language units run sequentially). ~1.5× the single-call ceiling so a
// multi-unit item still fits. On expiry the job (and its in-flight gateway call)
// is aborted; already-written fields stay persisted.
const JOB_MAX_MS = 45 * 60 * 1000;

// Generic website context handed to the gateway so it picks the right register
// and terminology. NOT translated — see gateway.clj context-block.
const SITE_CONTEXT = {
	site_name: 'Violina Petrychenko',
	site_description:
		'Website of the classical concert pianist Violina Petrychenko — concert programmes, CDs, press reviews and piano pedagogy.',
	domain: 'violina-petrychenko.de',
	audience: 'concert organisers, classical-music audiences and press',
	tone: 'formal and professional, third person',
};

// Field names that live on *_translations rows but are never translatable text.
const FIELD_BLOCKLIST = new Set([
	'status',
	'sort',
	'sprachcode', // legacy duplicate of languages_code on begruessung_translations
	'modified_on',
	'date_created',
	'date_updated',
	'user_created',
	'user_updated',
]);

function isEmpty(v) {
	return v === null || v === undefined || (typeof v === 'string' && v.trim() === '');
}

// --- schema introspection --------------------------------------------------

// For a parent collection, resolve everything we need about its translations.
// Returns null if the collection has no `translations` o2m relation.
function resolveTranslationMeta(collection, schema) {
	const rel = schema.relations.find(
		(r) => r.related_collection === collection && r.meta && r.meta.one_field === 'translations',
	);
	if (!rel) return null;

	const transCollection = rel.collection; // e.g. programme_translations, cds_translations
	const parentFkField = rel.field; // e.g. programme_id, cd_id
	const junctionField = rel.meta.junction_field; // e.g. languages_code

	// language-code field: the m2o from the translations collection to `languages`
	const langRel = schema.relations.find(
		(r) => r.collection === transCollection && r.related_collection === 'languages',
	);
	const langField = langRel ? langRel.field : junctionField || 'languages_code';

	return { collection, transCollection, parentFkField, langField };
}

// List every parent collection that has a translations relation.
function translatedCollections(schema) {
	return schema.relations
		.filter((r) => r.meta && r.meta.one_field === 'translations' && r.related_collection)
		.map((r) => r.related_collection)
		.filter((c, i, a) => a.indexOf(c) === i)
		.sort();
}

// Translatable text fields of a *_translations collection, with the gateway
// `format` for each. Uses FieldsService so we get interface + is_primary_key.
async function translatableFields(transCollection, meta, ctx) {
	const { services, getSchema, database } = ctx;
	const { FieldsService } = services;
	const fieldsService = new FieldsService({ schema: ctx.schema, knex: database });
	const fields = await fieldsService.readAll(transCollection);

	return fields
		.filter((f) => {
			if (['string', 'text'].indexOf(f.type) === -1) return false;
			if (f.schema && f.schema.is_primary_key) return false;
			if (f.field === meta.langField || f.field === meta.parentFkField) return false;
			if (FIELD_BLOCKLIST.has(f.field)) return false;
			// relational specials (m2o etc.) — exclude
			const special = (f.meta && f.meta.special) || [];
			if (special.some((s) => /^(m2o|o2m|m2m|m2a|file|files|translations)$/.test(s))) return false;
			return true;
		})
		.map((f) => ({
			field: f.field,
			format: f.meta && f.meta.interface === 'input-rich-text-html' ? 'html' : 'plain text',
		}));
}

// --- gateway ---------------------------------------------------------------

const GATEWAY_POLL_MS = 2000;

// Translate `text` via the gateway's async job API: POST /jobs starts the work
// and returns immediately; we poll GET /jobs/:id (no key — the id is the
// capability) until it finishes. Both requests are fast, so no held request
// exists for any proxy (undici/nginx) to time out. Returns { lang: text }.
async function callGateway(env, { text, targetLanguages, format, signal }) {
	const base = String(env.LLM_GATEWAY_URL || '').replace(/\/+$/, '');

	const startResp = await fetch(base + '/jobs', {
		method: 'POST',
		headers: {
			'Content-Type': 'application/json',
			Authorization: `Bearer ${env.LLM_GATEWAY_API_KEY}`,
		},
		body: JSON.stringify({
			text,
			target_languages: targetLanguages,
			source_language: SOURCE_LANG,
			format,
			context: SITE_CONTEXT,
		}),
		signal,
	});
	if (!startResp.ok) {
		const body = await startResp.text().catch(() => '');
		throw new Error(`gateway ${startResp.status}: ${body.slice(0, 200)}`);
	}
	const { jobId } = await startResp.json();
	if (!jobId) throw new Error('gateway did not return a jobId');

	for (;;) {
		if (signal && signal.aborted) throw new Error('aborted');
		await new Promise((r) => setTimeout(r, GATEWAY_POLL_MS));
		const pollResp = await fetch(`${base}/jobs/${encodeURIComponent(jobId)}`, { signal });
		if (!pollResp.ok) {
			const body = await pollResp.text().catch(() => '');
			throw new Error(`gateway job ${pollResp.status}: ${body.slice(0, 200)}`);
		}
		const data = await pollResp.json();
		if (data.status === 'done') return data.translations || {};
		if (data.status === 'error') throw new Error(`gateway job failed: ${data.error || 'unknown'}`);
		// status "running" → keep polling
	}
}

// --- shared core: translate one item ---------------------------------------

// Translate a single parent item from German into `languages`, one (field,
// language) unit at a time — each unit is a single short gateway call, so no
// individual call risks the Directus→gateway timeout. Reports progress through
// optional callbacks so a background job can surface it live:
//   onProgress({ total?, done?, current? })  — fields merged into the job state
//   onError(message)                         — called once per failed unit
// `onlyFields` (optional array of field names) restricts the work to those fields.
// Returns { hasGerman, filled: { <lang>: [fields...] } }.
async function translateItem({ collection, pk, languages, overwrite, ctx, onlyFields, onProgress, onError, signal }) {
	const { schema, services, database, accountability, env, logger } = ctx;
	const { ItemsService } = services;

	const meta = resolveTranslationMeta(collection, schema);
	if (!meta) throw new Error(`collection '${collection}' has no translations relation`);

	let fields = await translatableFields(meta.transCollection, meta, ctx);
	if (onlyFields && onlyFields.length) {
		const wanted = new Set(onlyFields);
		fields = fields.filter((f) => wanted.has(f.field));
	}
	const items = new ItemsService(meta.transCollection, { schema, accountability, knex: database });

	// German source row
	const sourceRows = await items.readByQuery({
		filter: { _and: [{ [meta.parentFkField]: { _eq: pk } }, { [meta.langField]: { _eq: SOURCE_LANG } }] },
		limit: 1,
	});
	const source = sourceRows[0];
	const filled = {};
	if (!source) {
		if (onProgress) onProgress({ total: 0, done: 0, current: null });
		return { hasGerman: false, filled };
	}

	// Read the existing target rows up front so we can decide what needs writing
	// BEFORE spending any (slow) gateway calls. Otherwise we'd translate every
	// field and only discard the result at upsert time when content already
	// exists and overwrite is off — burning an LLM call per already-filled field.
	const existingByLang = {};
	for (const lang of languages) {
		const rows = await items.readByQuery({
			filter: { _and: [{ [meta.parentFkField]: { _eq: pk } }, { [meta.langField]: { _eq: lang } }] },
			limit: 1,
		});
		existingByLang[lang] = rows[0] || null;
	}

	const pkField = schema.collections[meta.transCollection].primary;

	// A (field, lang) pair needs translating only when the German source has
	// content and the target is empty (or we're overwriting).
	const needs = (field, lang) => {
		if (isEmpty(source[field])) return false;
		const existing = existingByLang[lang];
		return overwrite || !(existing && !isEmpty(existing[field]));
	};

	// Work list: one unit per (field, language) that still needs translating.
	// Fields whose German source exceeds the per-unit char cap are skipped with
	// an error — they'd risk stalling the job past its time ceiling.
	const units = [];
	for (const { field, format } of fields) {
		const wantLangs = languages.filter((lang) => needs(field, lang));
		if (wantLangs.length === 0) continue;
		const len = String(source[field]).length;
		if (len > MAX_CHARS_PER_UNIT) {
			if (onError) {
				onError(`${field}: Quelltext zu lang (${len} Zeichen, max. ${MAX_CHARS_PER_UNIT}) — bitte manuell übersetzen`);
			}
			continue;
		}
		for (const lang of wantLangs) units.push({ field, format, lang });
	}
	if (onProgress) onProgress({ total: units.length, done: 0, current: null });

	let done = 0;
	for (const { field, format, lang } of units) {
		if (signal && signal.aborted) break; // job killed (time ceiling) → stop
		if (onProgress) onProgress({ current: `${field} → ${lang}` });
		try {
			const translations = await callGateway(env, {
				text: String(source[field]),
				targetLanguages: [lang],
				format,
				signal,
			});
			const value = translations[lang];
			if (!isEmpty(value)) {
				const existing = existingByLang[lang];
				if (existing) {
					await items.updateOne(existing[pkField], { [field]: value });
				} else {
					// First field written for this language creates the row; cache the
					// new pk so later fields UPDATE it instead of inserting duplicates.
					const newPk = await items.createOne({
						[meta.parentFkField]: pk,
						[meta.langField]: lang,
						...(source.status !== undefined ? { status: source.status } : {}),
						[field]: value,
					});
					existingByLang[lang] = { [pkField]: newPk };
				}
				(filled[lang] = filled[lang] || []).push(field);
			}
		} catch (err) {
			if (signal && signal.aborted) break; // aborted mid-call — reported by caller
			const msg = `${field} → ${lang}: ${err.message}`;
			logger.warn(`llm-translate: ${collection}#${pk} ${msg}`);
			if (onError) onError(msg);
		}
		done += 1;
		if (onProgress) onProgress({ done });
	}

	return { hasGerman: true, filled };
}

// --- async translate jobs --------------------------------------------------
// In-memory registry of running/finished jobs. This deployment runs a single
// Directus instance; jobs are ephemeral (lost on restart), but translated
// fields are persisted as they are written, so re-running resumes via the
// skip-already-translated logic. See the POST route for the rationale.
const jobs = new Map(); // jobId -> { status, total, done, current, errors, filled, hasGerman }
let jobSeq = 0;
const JOB_TTL_MS = 5 * 60 * 1000; // evict a finished job this long after it ends

// --- endpoint --------------------------------------------------------------

export default (router, ctxBase) => {
	const { services, getSchema, database, env, logger } = ctxBase;
	const { ItemsService } = services;

	function requireConfig(res) {
		if (!env.LLM_GATEWAY_URL || !env.LLM_GATEWAY_API_KEY) {
			res.status(500).json({ error: 'LLM_GATEWAY_URL / LLM_GATEWAY_API_KEY not configured' });
			return false;
		}
		return true;
	}

	async function targetLangs(schema, accountability) {
		const langs = new ItemsService('languages', { schema, accountability, knex: database });
		const rows = await langs.readByQuery({ fields: ['code', 'langname'], limit: -1 });
		return rows
			.filter((l) => l.code !== SOURCE_LANG)
			.map((l) => ({ code: l.code, label: l.langname || l.code }));
	}

	// List of translated collections + available target languages.
	router.get('/collections', async (req, res) => {
		try {
			const schema = await getSchema();
			res.json({
				source: SOURCE_LANG,
				targets: await targetLangs(schema, req.accountability),
				collections: translatedCollections(schema),
			});
		} catch (err) {
			logger.error(err);
			res.status(500).json({ error: err.message });
		}
	});

	// Info for the per-item interface: which languages are empty (pre-checked).
	router.get('/:collection/:pk/info', async (req, res) => {
		try {
			const schema = await getSchema();
			const { collection, pk } = req.params;
			const meta = resolveTranslationMeta(collection, schema);
			if (!meta) return res.status(400).json({ error: 'not a translated collection' });

			const ctx = { schema, services, database, accountability: req.accountability, env, logger };
			const fields = await translatableFields(meta.transCollection, meta, ctx);
			const items = new ItemsService(meta.transCollection, {
				schema,
				accountability: req.accountability,
				knex: database,
			});
			const rows = await items.readByQuery({
				filter: { [meta.parentFkField]: { _eq: pk } },
				limit: -1,
			});
			const byLang = {};
			for (const r of rows) byLang[r[meta.langField]] = r;

			const targets = (await targetLangs(schema, req.accountability)).map((t) => {
				const row = byLang[t.code];
				const empty = !row || fields.every((f) => isEmpty(row[f.field]));
				return { ...t, empty };
			});

			res.json({
				hasGerman: !isEmpty(byLang[SOURCE_LANG]) && fields.some((f) => !isEmpty((byLang[SOURCE_LANG] || {})[f.field])),
				fields: fields.map((f) => f.field),
				targets,
			});
		} catch (err) {
			logger.error(err);
			res.status(500).json({ error: err.message });
		}
	});

	// Translate one item.
	router.post('/all', async (req, res) => {
		if (!requireConfig(res)) return;
		try {
			const schema = await getSchema();
			const only = req.query.collection;
			const all = translatedCollections(schema);
			const collections = only ? all.filter((c) => c === only) : all;
			if (only && collections.length === 0) return res.status(400).json({ error: `unknown collection '${only}'` });

			const languages = (await targetLangs(schema, req.accountability)).map((t) => t.code);
			const summary = {};

			for (const collection of collections) {
				const meta = resolveTranslationMeta(collection, schema);
				const pkField = schema.collections[collection].primary;
				const parents = new ItemsService(collection, {
					schema,
					accountability: req.accountability,
					knex: database,
				});
				const rows = await parents.readByQuery({ fields: [pkField], limit: -1 });

				let items = 0;
				let fieldsFilled = 0;
				for (const row of rows) {
					const ctx = { schema, services, database, accountability: req.accountability, env, logger };
					const result = await translateItem({
						collection,
						pk: row[pkField],
						languages,
						overwrite: false,
						ctx,
					});
					if (result.hasGerman) {
						const n = Object.values(result.filled).reduce((a, fs) => a + fs.length, 0);
						if (n > 0) items += 1;
						fieldsFilled += n;
					}
				}
				summary[collection] = { items, fieldsFilled };
			}

			res.json({ ok: true, summary });
		} catch (err) {
			logger.error(err);
			res.status(500).json({ error: err.message });
		}
	});

	// Start a translate job. The gateway is slow (a long field can take minutes
	// per language on CPU), so we never hold the HTTP request open for the whole
	// item — that dies at the reverse-proxy read timeout (undici in dev, nginx in
	// prod) → 502. POST returns a jobId immediately; the interface polls
	// GET .../job/:jobId. A server-side watchdog aborts the job at JOB_MAX_MS.
	router.post('/:collection/:pk', async (req, res) => {
		if (!requireConfig(res)) return;
		try {
			const schema = await getSchema();
			const { collection, pk } = req.params;
			const languages = Array.isArray(req.body && req.body.languages) ? req.body.languages : [];
			const overwrite = !!(req.body && req.body.overwrite);
			if (languages.length === 0) return res.status(400).json({ error: 'languages must be a non-empty array' });

			const meta = resolveTranslationMeta(collection, schema);
			if (!meta) return res.status(400).json({ error: 'not a translated collection' });

			const jobId = `${collection}:${pk}:${++jobSeq}`;
			const job = { id: jobId, status: 'running', total: 0, done: 0, current: null, errors: [], filled: {}, hasGerman: true };
			jobs.set(jobId, job);

			// Kill switch: abort the job (and its in-flight gateway call) at the ceiling.
			const controller = new AbortController();
			const killer = setTimeout(() => controller.abort(), JOB_MAX_MS);
			if (killer.unref) killer.unref();

			// Fire and forget — the browser polls for progress. Capture the request's
			// accountability so the background writes keep the same permissions.
			const accountability = req.accountability;
			(async () => {
				try {
					const ctx = { schema, services, database, accountability, env, logger };
					const result = await translateItem({
						collection,
						pk,
						languages,
						overwrite,
						ctx,
						signal: controller.signal,
						onProgress: (p) => Object.assign(job, p),
						onError: (msg) => job.errors.push(msg),
					});
					job.hasGerman = result.hasGerman;
					job.filled = result.filled;
					if (controller.signal.aborted) {
						job.errors.push(`Zeitlimit (${Math.round(JOB_MAX_MS / 60000)} min) überschritten — Job abgebrochen`);
						job.status = 'error';
					} else {
						job.status = job.errors.length ? 'error' : 'done';
					}
				} catch (err) {
					logger.error(err);
					job.errors.push(err.message);
					job.status = 'error';
				} finally {
					clearTimeout(killer);
					const evict = setTimeout(() => jobs.delete(jobId), JOB_TTL_MS);
					if (evict.unref) evict.unref();
				}
			})();

			res.status(202).json({ jobId, status: 'running' });
		} catch (err) {
			logger.error(err);
			res.status(500).json({ error: err.message });
		}
	});

	// Poll a translate job's progress/result.
	router.get('/:collection/:pk/job/:jobId', (req, res) => {
		const job = jobs.get(req.params.jobId);
		if (!job) return res.status(404).json({ error: 'unknown or expired job' });
		const { id, status, total, done, current, errors, filled, hasGerman } = job;
		res.json({ id, status, total, done, current, errors, filled, hasGerman });
	});
};
