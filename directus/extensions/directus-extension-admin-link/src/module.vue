<template>
	<private-view title="Festival Admin">
		<div class="admin-link">
			<a href="/de/admin">Open Festival Admin →</a>
		</div>
	</private-view>
</template>

<script>
export default {};
</script>

<style scoped>
.admin-link {
	padding: 32px;
	font-size: 1.125rem;
}
.admin-link a {
	color: var(--primary, #4caf50);
	text-decoration: none;
}
.admin-link a:hover {
	text-decoration: underline;
}
</style>
