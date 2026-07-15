import InterfaceComponent from './interface.vue';

export default {
	id: 'llm-translate',
	name: 'LLM-Übersetzung',
	icon: 'translate',
	description: 'Übersetzt die Felder dieses Eintrags aus dem Deutschen via LLM-Gateway.',
	component: InterfaceComponent,
	// Presentation-only alias interface — stores no value of its own.
	types: ['alias'],
	localTypes: ['presentation'],
	group: 'presentation',
	options: null,
};
