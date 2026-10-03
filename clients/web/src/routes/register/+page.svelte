<script lang="ts">
	import { t, friendlyError } from '$lib/i18n/index.svelte';
	import { goto } from '$app/navigation';
	import { register, getServerConfig } from '$lib/api/auth';
	import { authStore } from '$lib/stores/auth.svelte';
	import { isTauriEnv, getServerUrl, clearServerUrl } from '$lib/env';
	import { onMount } from 'svelte';
	import { initCrypto, getKeyManager } from '$lib/crypto';

	let serverUrl = $derived(isTauriEnv() ? getServerUrl() : null);

	function changeServer() {
		clearServerUrl();
		authStore.logout();
		goto('/connect');
	}

	let username = $state('');
	let email = $state('');
	let displayName = $state('');
	let password = $state('');
	let confirmPassword = $state('');
	let inviteCode = $state('');
	let error = $state('');
	let loading = $state(false);
	let showPassword = $state(false);
	let showConfirmPassword = $state(false);
	let registrationMode = $state('open');
	let configLoading = $state(true);
	let recoveryCode = $state('');
	let showRecoveryModal = $state(false);
	let copiedRecovery = $state(false);

	let pwMatch = $derived(confirmPassword.length > 0 && password === confirmPassword);
	let pwMismatch = $derived(confirmPassword.length > 0 && password !== confirmPassword);

	// Password strength checks
	let pwHasLength = $derived(password.length >= 8);
	let pwHasUpper = $derived(/[A-Z]/.test(password));
	let pwHasLower = $derived(/[a-z]/.test(password));
	let pwHasDigit = $derived(/[0-9]/.test(password));
	let pwHasSpecial = $derived(/[^A-Za-z0-9]/.test(password));
	let pwAllMet = $derived(pwHasLength && pwHasUpper && pwHasLower && pwHasDigit && pwHasSpecial);

	onMount(async () => {
		try {
			const config = await getServerConfig();
			registrationMode = config.registration_mode;
		} catch {
			// If we can't fetch config, assume open
		} finally {
			configLoading = false;
		}
	});

	async function handleSubmit(e: SubmitEvent) {
		e.preventDefault();
		error = '';

		if (!pwAllMet) {
			error = t('error.pwRequirements');
			return;
		}
		if (password !== confirmPassword) {
			error = t('error.pwMismatch');
			return;
		}

		loading = true;

		try {
			// Generate real E2E encryption keys via WASM crypto module
			await initCrypto();
			const keys = await getKeyManager().generateRegistrationKeys();

			const response = await register({
				username: username.trim(),
				email: email.trim(),
				password,
				display_name: (displayName || username).trim(),
				identity_key: keys.identityKey,
				signed_prekey: keys.signedPrekey,
				one_time_prekeys: keys.oneTimePrekeys,
				invite_code: registrationMode === 'invite_only' ? inviteCode : undefined
			});

			authStore.setAuth(response.access_token, response.refresh_token, response.user);
			if (response.recovery_code) {
				recoveryCode = response.recovery_code;
				showRecoveryModal = true;
			} else {
				goto('/channels');
			}
		} catch (err) {
			error = friendlyError(err, 'reg.failed');
		} finally {
			loading = false;
		}
	}
</script>

<div class="flex min-h-screen items-center justify-center p-3 sm:p-4">
	<div class="w-full max-w-md rounded-2xl bg-[var(--bg-secondary)] p-5 sm:p-8 shadow-2xl">
		<h1 class="mb-2 text-center text-3xl font-bold text-[var(--text-primary)]">TS</h1>

		{#if serverUrl}
			<div class="mb-4 flex items-center justify-center gap-2 text-xs text-[var(--text-secondary)]">
				<span>{t('common.connectedTo',{url:serverUrl ?? ''})}</span>
				<button onclick={changeServer} class="text-[var(--accent)] hover:underline">{t('common.change')}</button>
			</div>
		{/if}

		{#if configLoading}
			<div class="flex justify-center py-8">
				<div class="h-6 w-6 animate-spin rounded-full border-2 border-[var(--accent)] border-t-transparent"></div>
			</div>
		{:else if registrationMode === 'closed'}
			<p class="mb-4 text-center text-[var(--text-secondary)]">{t('reg.closed')}</p>
			<p class="text-center text-sm text-[var(--text-secondary)]">
				{t('reg.closedHint')}
			</p>
			<p class="mt-4 text-center text-sm text-[var(--text-secondary)]">
				{t('reg.haveAccount')}
				<a href="/login" class="text-[var(--accent)] transition hover:text-[var(--accent-hover)]">
										{t('reg.signIn')}

				</a>
			</p>
		{:else}
			<p class="mb-8 text-center text-[var(--text-secondary)]">{t('reg.title')}</p>

			{#if error}
				<div class="mb-4 rounded-lg bg-red-500/10 p-3 text-sm text-[var(--danger)]" role="alert">
					{error}
				</div>
			{/if}

			<form onsubmit={handleSubmit} class="space-y-4">
				{#if registrationMode === 'invite_only'}
					<div>
						<label for="invite-code" class="mb-1 block text-sm font-medium text-[var(--text-secondary)]">
							{t('reg.inviteCode')}

						</label>
						<input
							id="invite-code"
							type="text"
							bind:value={inviteCode}
							required
							placeholder={t('reg.invitePlaceholder')}
							class="w-full rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] px-4 py-2.5 font-mono tracking-wider text-[var(--text-primary)] outline-none transition focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent)]/30"
						/>
						<p class="mt-1 text-xs text-[var(--text-secondary)]">
							{t('reg.inviteHint')}
						</p>
					</div>
				{/if}

				<div>
					<label for="username" class="mb-1 block text-sm font-medium text-[var(--text-secondary)]">
						{t('reg.username')}
					</label>
					<input
						id="username"
						type="text"
						bind:value={username}
						required
						autofocus
						minlength="3"
						maxlength="32"
						autocomplete="username"
						class="w-full rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] px-4 py-2.5 text-[var(--text-primary)] outline-none transition focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent)]/30"
					/>
					<p class="mt-1 text-xs text-[var(--text-secondary)]/70">
						{t('reg.usernameHint')}
					</p>
				</div>

				<div>
					<label for="display-name" class="mb-1 block text-sm font-medium text-[var(--text-secondary)]">
						{t('reg.displayName')} <span class="text-[var(--text-secondary)]/50">{t('reg.optional')}</span>
					</label>
					<input
						id="display-name"
						type="text"
						bind:value={displayName}
						maxlength="64"
						placeholder={username || t('reg.displayPlaceholder')}
						class="w-full rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] px-4 py-2.5 text-[var(--text-primary)] outline-none transition focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent)]/30"
					/>
				</div>

				<div>
					<label for="email" class="mb-1 block text-sm font-medium text-[var(--text-secondary)]">
						{t('reg.email')}

					</label>
					<input
						id="email"
						type="email"
						bind:value={email}
						required
						autocomplete="email"
						class="w-full rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] px-4 py-2.5 text-[var(--text-primary)] outline-none transition focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent)]/30"
					/>
				</div>

				<div>
					<label for="password" class="mb-1 block text-sm font-medium text-[var(--text-secondary)]">
						{t('reg.password')}
					</label>
					<div class="relative">
						<input
							id="password"
							type={showPassword ? 'text' : 'password'}
							bind:value={password}
							required
							minlength="8"
							autocomplete="new-password"
							class="w-full rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] px-4 py-2.5 pr-10 text-[var(--text-primary)] outline-none transition focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent)]/30"
						/>
						<button
							type="button"
							onclick={() => showPassword = !showPassword}
							class="absolute right-3 top-1/2 -translate-y-1/2 text-[var(--text-secondary)] transition hover:text-[var(--text-primary)]"
							aria-label={showPassword ? t('common.hidePassword') : t('common.showPassword')}
						>
							{#if showPassword}
								<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
							{:else}
								<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
							{/if}
						</button>
					</div>
					{#if password.length > 0}
						<div class="mt-2 space-y-1 text-xs">
							<div class={pwHasLength ? 'text-green-400' : 'text-[var(--text-secondary)]'}>
								{pwHasLength ? '✓' : '✗'} {t('reg.pw.len')}
							</div>
							<div class={pwHasUpper ? 'text-green-400' : 'text-[var(--text-secondary)]'}>
								{pwHasUpper ? '✓' : '✗'} {t('reg.pw.upper')}
							</div>
							<div class={pwHasLower ? 'text-green-400' : 'text-[var(--text-secondary)]'}>
								{pwHasLower ? '✓' : '✗'} {t('reg.pw.lower')}
							</div>
							<div class={pwHasDigit ? 'text-green-400' : 'text-[var(--text-secondary)]'}>
								{pwHasDigit ? '✓' : '✗'} {t('reg.pw.digit')}
							</div>
							<div class={pwHasSpecial ? 'text-green-400' : 'text-[var(--text-secondary)]'}>
								{pwHasSpecial ? '✓' : '✗'} {t('reg.pw.special')}
							</div>
						</div>
					{/if}
				</div>

				<div>
					<label for="confirm-password" class="mb-1 block text-sm font-medium text-[var(--text-secondary)]">
						{t('reg.confirm')}
					</label>
					<div class="relative">
						<input
							id="confirm-password"
							type={showConfirmPassword ? 'text' : 'password'}
							bind:value={confirmPassword}
							required
							autocomplete="new-password"
							class="w-full rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] px-4 py-2.5 pr-10 text-[var(--text-primary)] outline-none transition focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent)]/30"
						/>
						<button
							type="button"
							onclick={() => showConfirmPassword = !showConfirmPassword}
							class="absolute right-3 top-1/2 -translate-y-1/2 text-[var(--text-secondary)] transition hover:text-[var(--text-primary)]"
							aria-label={showConfirmPassword ? t('common.hidePassword') : t('common.showPassword')}
						>
							{#if showConfirmPassword}
								<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
							{:else}
								<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
							{/if}
						</button>
					</div>
					{#if pwMatch}
						<p class="mt-1 text-xs text-green-400">{t('reg.pwMatch')}</p>
					{:else if pwMismatch}
						<p class="mt-1 text-xs text-[var(--danger)]">{t('reg.pwMismatch')}</p>
					{/if}
				</div>

				<button
					type="submit"
					disabled={loading}
					class="w-full rounded-xl bg-[var(--accent)] px-4 py-2.5 font-medium text-white transition hover:bg-[var(--accent-hover)] disabled:cursor-not-allowed disabled:opacity-50"
				>
					{#if loading}
						<span class="inline-flex items-center gap-2">
							<svg class="h-4 w-4 animate-spin" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="3" class="opacity-25"/><path d="M4 12a8 8 0 018-8" stroke="currentColor" stroke-width="3" stroke-linecap="round" class="opacity-75"/></svg>
							{t('reg.submitting')}
						</span>
					{:else}
												{t('reg.submit')}

					{/if}
				</button>

				<p class="text-xs text-[var(--text-secondary)]/70">
					{t('reg.agree')}
					<a href="/terms" class="text-[var(--accent)] hover:underline">{t('reg.terms')}</a>
					
					{t('reg.and')}

					<a href="/privacy" class="text-[var(--accent)] hover:underline">{t('reg.privacy')}</a>.
				</p>
			</form>

			<p class="mt-4 text-center text-sm text-[var(--text-secondary)]">
				{t('reg.haveAccount')}
				<a href="/login" class="text-[var(--accent)] transition hover:text-[var(--accent-hover)]">
										{t('reg.signIn')}

				</a>
			</p>
		{/if}
	</div>
</div>

{#if showRecoveryModal}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div
		class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
		role="dialog"
		aria-label={t('reg.recovery.aria')}
		onkeydown={(e) => { if (e.key === 'Escape') goto('/channels'); e.stopPropagation(); }}
	>
		<div class="w-full max-w-md rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-2xl">
			<h2 class="mb-2 text-xl font-bold text-[var(--text-primary)]">{t('reg.recovery.title')}</h2>
			<p class="mb-4 text-sm text-[var(--text-secondary)]">
				{t('reg.recovery.body')}
			</p>

			<div class="mb-4 rounded-lg bg-[var(--bg-primary)] p-4 text-center">
				<code class="select-all font-mono text-lg font-bold tracking-wider text-[var(--accent)]">
					{recoveryCode}
				</code>
			</div>

			<div class="flex gap-3">
				<button
					onclick={() => {
						navigator.clipboard.writeText(recoveryCode).then(() => {
							copiedRecovery = true;
							setTimeout(() => (copiedRecovery = false), 2000);
						}).catch(() => { /* clipboard may be unavailable */ });
					}}
					class="flex-1 rounded-xl border border-[var(--border)] px-4 py-2.5 text-sm font-medium text-[var(--text-primary)] transition hover:bg-white/5"
				>
					{copiedRecovery ? t('common.copied') : t('common.copyCode')}
				</button>
				<button
					onclick={() => goto('/channels')}
					class="flex-1 rounded-xl bg-[var(--accent)] px-4 py-2.5 text-sm font-medium text-white transition hover:bg-[var(--accent-hover)]"
				>
					{t('reg.recovery.saved')}
				</button>
			</div>
		</div>
	</div>
{/if}
