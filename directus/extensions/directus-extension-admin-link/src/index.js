import ModuleComponent from './module.vue';

export default {
	id: 'admin-app',
	name: 'Violina Admin',
	icon: 'launch',
	routes: [
		{
			path: '',
			component: ModuleComponent,
		},
	],
};
