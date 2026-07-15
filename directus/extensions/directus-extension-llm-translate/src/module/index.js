import ModuleComponent from './module.vue';

export default {
	id: 'llm-translate',
	name: 'Übersetzungen',
	icon: 'translate',
	routes: [
		{
			path: '',
			component: ModuleComponent,
		},
	],
};
