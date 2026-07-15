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

async function callGateway(env, { text, targetLanguages, format }) {
	const base = String(env.LLM_GATEWAY_URL || '').replace(/\/+$/, '');
	const resp = await fetch(base + '/translate', {
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
	});
	if (!resp.ok) {
		const body = await resp.text().catch(() => '');
		throw new Error(`gateway ${resp.status}: ${body.slice(0, 200)}`);
	}
	const data = await resp.json();
	return data.translations || {};
}

// --- shared core: translate one item ---------------------------------------

// Translate a single parent item from German into `languages`.
// Returns { hasGerman, filled: { <lang>: [fields...] } }.
async function translateItem({ collection, pk, languages, overwrite, ctx }) {
	const { schema, services, database, accountability, env, logger } = ctx;
	const { ItemsService } = services;

	const meta = resolveTranslationMeta(collection, schema);
	if (!meta) throw new Error(`collection '${collection}' has no translations relation`);

	const fields = await translatableFields(meta.transCollection, meta, ctx);
	const items = new ItemsService(meta.transCollection, { schema, accountability, knex: database });

	// German source row
	const sourceRows = await items.readByQuery({
		filter: { _and: [{ [meta.parentFkField]: { _eq: pk } }, { [meta.langField]: { _eq: SOURCE_LANG } }] },
		limit: 1,
	});
	const source = sourceRows[0];
	const filled = {};
	if (!source) return { hasGerman: false, filled };

	// One gateway call per field (each returns all target languages).
	const perField = {}; // field -> { lang -> text }
	for (const { field, format } of fields) {
		if (isEmpty(source[field])) continue;
		try {
			perField[field] = await callGateway(env, {
				text: String(source[field]),
				targetLanguages: languages,
				format,
			});
		} catch (err) {
			logger.warn(`llm-translate: ${collection}#${pk} field '${field}' failed: ${err.message}`);
		}
	}

	// Upsert each target-language row.
	for (const lang of languages) {
		const existingRows = await items.readByQuery({
			filter: { _and: [{ [meta.parentFkField]: { _eq: pk } }, { [meta.langField]: { _eq: lang } }] },
			limit: 1,
		});
		const existing = existingRows[0];

		const payload = {};
		for (const { field } of fields) {
			const translated = perField[field] && perField[field][lang];
			if (isEmpty(translated)) continue;
			const keep = existing && !isEmpty(existing[field]);
			if (keep && !overwrite) continue; // preserve human-edited content
			payload[field] = translated;
		}
		if (Object.keys(payload).length === 0) continue;

		if (existing) {
			await items.updateOne(existing[schema.collections[meta.transCollection].primary], payload);
		} else {
			await items.createOne({
				[meta.parentFkField]: pk,
				[meta.langField]: lang,
				...(source.status !== undefined ? { status: source.status } : {}),
				...payload,
			});
		}
		filled[lang] = Object.keys(payload);
	}

	return { hasGerman: true, filled };
}

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

	router.post('/:collection/:pk', async (req, res) => {
		if (!requireConfig(res)) return;
		try {
			const schema = await getSchema();
			const { collection, pk } = req.params;
			const languages = Array.isArray(req.body && req.body.languages) ? req.body.languages : [];
			const overwrite = !!(req.body && req.body.overwrite);
			if (languages.length === 0) return res.status(400).json({ error: 'languages must be a non-empty array' });

			const ctx = { schema, services, database, accountability: req.accountability, env, logger };
			const result = await translateItem({ collection, pk, languages, overwrite, ctx });
			if (!result.hasGerman) return res.status(400).json({ error: 'no German source content to translate' });

			res.json({ ok: true, ...result });
		} catch (err) {
			logger.error(err);
			res.status(500).json({ error: err.message });
		}
	});
};
