import ModuleComponent from './module.vue';

export default {
	id: 'admin-app',
	name: 'Festival Admin',
	icon: 'launch',
	routes: [
		{
			path: '',
			component: ModuleComponent,
		},
	],
};
