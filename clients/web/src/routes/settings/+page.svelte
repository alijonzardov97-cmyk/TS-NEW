<script lang="ts">
	import { t as _t } from '$lib/i18n/index.svelte';
	import { goto } from '$app/navigation';
	import { fade, scale } from 'svelte/transition';
	import { authStore } from '$lib/stores/auth.svelte';
	import { themeStore } from '$lib/stores/theme.svelte';
	import { i18n, t, LOCALES } from '$lib/i18n/index.svelte';
	import { toastStore } from '$lib/stores/toast.svelte';
	import { soundStore } from '$lib/stores/sound.svelte';
	import { notificationStore } from '$lib/stores/notification.svelte';
	import { pushStore } from '$lib/stores/push.svelte';
	import { preferencesStore, ACCENT_COLORS, FONT_SIZES, PRESET_THEMES, VOICE_BG_PRESETS, voiceBackgroundStyle, type AccentColor, type NoiseSuppression, type PresetTheme, type VoiceBackgroundType, type VoiceActivationMode } from '$lib/stores/preferences.svelte';
	import { webrtcManager } from '$lib/webrtc/manager';
	import { userStore } from '$lib/stores/users.svelte';
	import { voiceStore } from '$lib/stores/voice.svelte';
	import { audioDeviceStore } from '$lib/stores/audioDevices.svelte';
	import { setupTotp, verifyTotp, disableTotp, regenerateBackupCodes, type TotpSetup } from '$lib/api/totp';
	import { changePassword, updateProfile, uploadAvatar, uploadBanner, uploadVoiceBackground, clearVoiceBackground, deleteAccount, logoutAll, listSessions, revokeSession, regenerateRecoveryCode, type SessionInfo } from '$lib/api/account';
	import { isTauri, isTauriEnv, getServerUrl, clearServerUrl } from '$lib/env';
	import { initCrypto, getKeyManager } from '$lib/crypto';
	import { getCrypto } from '$lib/crypto/wasm-loader';
	import QRCode from 'qrcode';
	import Avatar from '$lib/components/Avatar.svelte';
	import ImageCropper from '$lib/components/ImageCropper.svelte';
	import { onMount, onDestroy } from 'svelte';

	type Tab = 'profile' | 'appearance' | 'notifications' | 'chat' | 'voice' | 'security' | 'account';
	let activeTab = $state<Tab>('profile');

	let isDesktop = $derived(isTauriEnv());
	let serverUrl = $derived(isDesktop ? getServerUrl() : null);

	let totpSetup = $state<TotpSetup | null>(null);
	let totpQrDataUrl = $state('');
	let totpCode = $state('');
	let disableCode = $state('');
	let totpMessage = $state('');
	let totpError = $state('');
	let showTotpSetup = $state(false);
	let showTotpDisable = $state(false);
	let backupCodes = $state<string[]>([]);
	let showBackupCodes = $state(false);
	let backupCodeTotpInput = $state('');
	let backupCodeError = $state('');

	// Recovery code
	let recoveryCode = $state('');
	let showRecoveryCode = $state(false);
	let recoveryLoading = $state(false);
	let recoveryError = $state('');
	let copiedRecovery = $state(false);

	// Profile editing
	let editDisplayName = $state(authStore.user?.display_name ?? '');
	let editCustomStatus = $state(authStore.user?.custom_status ?? '');
	let editBio = $state(authStore.user?.bio ?? '');
	let editPronouns = $state(authStore.user?.pronouns ?? '');
	let profileSaving = $state(false);
	let profileMessage = $state('');
	let profileError = $state('');
	let profileMsgTimer: ReturnType<typeof setTimeout> | null = null;

	function setProfileMessage(msg: string) {
		profileMessage = msg;
		if (profileMsgTimer) clearTimeout(profileMsgTimer);
		profileMsgTimer = setTimeout(() => { profileMessage = ''; }, 4000);
	}

	// Avatar upload
	let avatarInputEl: HTMLInputElement | undefined = $state();
	let avatarUploading = $state(false);

	// Banner upload
	let bannerInputEl: HTMLInputElement | undefined = $state();
	let bannerUploading = $state(false);

	// Image cropper state
	let cropFile = $state<File | null>(null);
	let cropTarget = $state<'avatar' | 'banner' | 'voiceBg' | null>(null);

	// Change password
	let currentPassword = $state('');
	let newPassword = $state('');
	let confirmPassword = $state('');
	let passwordSaving = $state(false);
	let passwordError = $state('');

	// Password strength checks
	let pwHasLength = $derived(newPassword.length >= 8);
	let pwHasUpper = $derived(/[A-Z]/.test(newPassword));
	let pwHasLower = $derived(/[a-z]/.test(newPassword));
	let pwHasDigit = $derived(/[0-9]/.test(newPassword));
	let pwHasSpecial = $derived(/[^A-Za-z0-9]/.test(newPassword));
	let pwAllMet = $derived(pwHasLength && pwHasUpper && pwHasLower && pwHasDigit && pwHasSpecial);

	// Sessions
	let sessions = $state<SessionInfo[]>([]);
	let sessionsLoading = $state(false);
	let sessionsError = $state('');
	let revokingSessionId = $state<string | null>(null);
	let revokingAll = $state(false);

	// Confirm dialog
	let settingsConfirmDialog = $state<{ title: string; message: string; confirmLabel: string; danger?: boolean; onConfirm: () => void } | null>(null);

	// Delete account
	let showDeleteConfirm = $state(false);
	let deletePassword = $state('');
	let deleteError = $state('');
	let deleting = $state(false);

	// Encryption info
	let ownFingerprintSettings = $state('');
	let ownPublicKeyHex = $state('');
	let fingerprintCopied = $state(false);
	let fingerprintLoaded = $state(false);
	let fingerprintLoading = $state(false);
	let fingerprintError = $state('');

	async function loadOwnFingerprint() {
		if (fingerprintLoaded || fingerprintLoading) return;
		fingerprintLoading = true;
		fingerprintError = '';
		try {
			await initCrypto();
			// Ensure keys exist (generates + uploads if missing, e.g. pre-E2E accounts)
			await getKeyManager().ensureKeysRegistered();
			const crypto = await getCrypto();
			const ownKey = await getKeyManager().getVerifyingKey();
			ownFingerprintSettings = crypto.compute_fingerprint(ownKey);
			ownPublicKeyHex = Array.from(ownKey).map(b => b.toString(16).padStart(2, '0')).join('');
			fingerprintLoaded = true;
		} catch (err) {
			console.error('Failed to load fingerprint:', err);
			fingerprintError = 'Failed to load encryption keys. Please try refreshing the page.';
		} finally {
			fingerprintLoading = false;
		}
	}

	const tabs: { id: Tab; label: string; icon: string }[] = [
		{ id: 'profile', label: 'Profile', icon: 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8z' },
		{ id: 'appearance', label: 'Appearance', icon: 'M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0z' },
		{ id: 'notifications', label: 'Notifications', icon: 'M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9M13.73 21a2 2 0 0 1-3.46 0' },
		{ id: 'chat', label: 'Chat', icon: 'M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z' },
		{ id: 'voice', label: 'Voice', icon: 'M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z M19 10v2a7 7 0 0 1-14 0v-2 M12 19v4 M8 23h8' },
		{ id: 'security', label: 'Security', icon: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z' },
		{ id: 'account', label: 'Account', icon: 'M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 0 0 2.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 0 0 1.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 0 0-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 0 0-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 0 0-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 0 0-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 0 0 1.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z' },
	];

	// Mic test state
	let testStream: MediaStream | null = null;
	let testAudioCtx: AudioContext | null = null;
	let testAnalyser: AnalyserNode | null = null;
	let testLevel = $state(0);
	let testActive = $state(false);
	let testRafId = 0;

	// Voice activation keybind recording
	let recordingKeybind = $state<'ptt' | 'toggle' | null>(null);

	const voiceActivationModes: { id: VoiceActivationMode; label: string; desc: string }[] = [
		{ id: 'open-mic', label: 'Open Mic', desc: 'Microphone is always active' },
		{ id: 'push-to-talk', label: 'Push to Talk', desc: 'Hold a key to transmit' },
		{ id: 'toggle-mute', label: 'Toggle Mute', desc: 'Press a key to toggle mic' },
	];

	function formatKeyForDisplay(key: string): string {
		if (key === ' ') return 'Space';
		if (key.length === 1) return key.toUpperCase();
		return key;
	}

	let activeKeyHandler: ((e: KeyboardEvent) => void) | null = null;
	let activeClickHandler: (() => void) | null = null;

	function cancelRecordingKey() {
		if (activeKeyHandler) window.removeEventListener('keydown', activeKeyHandler, true);
		if (activeClickHandler) window.removeEventListener('click', activeClickHandler, true);
		activeKeyHandler = null;
		activeClickHandler = null;
		recordingKeybind = null;
	}

	function startRecordingKey(target: 'ptt' | 'toggle') {
		cancelRecordingKey();
		recordingKeybind = target;
		const handler = (e: KeyboardEvent) => {
			e.preventDefault();
			e.stopPropagation();
			if (['Control', 'Shift', 'Alt', 'Meta'].includes(e.key)) return;
			preferencesStore.set(target === 'ptt' ? 'pttKey' : 'toggleMuteKey', e.key);
			cancelRecordingKey();
		};
		const clickHandler = () => {
			cancelRecordingKey();
		};
		activeKeyHandler = handler;
		activeClickHandler = clickHandler;
		window.addEventListener('keydown', handler, true);
		// Cancel if user clicks away (delay to avoid catching the triggering click)
		setTimeout(() => window.addEventListener('click', clickHandler, true), 0);
	}

	// Voice background state
	let voiceBgType = $state<VoiceBackgroundType>(preferencesStore.preferences.voiceBackground.type);
	let voiceBgColor = $state(preferencesStore.preferences.voiceBackground.color ?? '#1a1a2e');
	let voiceBgGradFrom = $state(preferencesStore.preferences.voiceBackground.gradientFrom ?? '#ff6b2b');
	let voiceBgGradTo = $state(preferencesStore.preferences.voiceBackground.gradientTo ?? '#6c3483');
	let voiceBgGradAngle = $state(preferencesStore.preferences.voiceBackground.gradientAngle ?? 135);
	let voiceBgPresetId = $state(preferencesStore.preferences.voiceBackground.presetId ?? 'fireplace');
	let voiceBgCustomUrl = $state(preferencesStore.preferences.voiceBackground.customUrl ?? '');
	let voiceBgUploading = $state(false);
	let voiceBgInputEl = $state<HTMLInputElement | null>(null);

	function applyVoiceBg() {
		const bg = {
			type: voiceBgType,
			color: voiceBgColor,
			gradientFrom: voiceBgGradFrom,
			gradientTo: voiceBgGradTo,
			gradientAngle: voiceBgGradAngle,
			presetId: voiceBgPresetId,
			customUrl: voiceBgCustomUrl,
		};
		preferencesStore.set('voiceBackground', bg);
		// Clear server-side voice background when switching away from custom image
		if (voiceBgType !== 'custom') {
			clearVoiceBackground().catch(() => {});
		}
	}

	function handleVoiceBgUpload(e: Event) {
		const file = (e.target as HTMLInputElement).files?.[0];
		if (!file) return;
		if (voiceBgInputEl) voiceBgInputEl.value = '';
		if (file.type === 'image/gif') {
			cropTarget = 'voiceBg';
			uploadCroppedImage(file);
			return;
		}
		cropFile = file;
		cropTarget = 'voiceBg';
	}

	async function startMicTest() {
		try {
			const constraints: MediaTrackConstraints = {
				noiseSuppression: false,
				echoCancellation: preferencesStore.preferences.echoCancellation,
				autoGainControl: preferencesStore.preferences.autoGainControl,
			};
			const inputId = audioDeviceStore.selectedInputId;
			if (inputId) constraints.deviceId = { exact: inputId };

			testStream = await navigator.mediaDevices.getUserMedia({ audio: constraints });
			// Re-enumerate now that we have permission (labels become available)
			audioDeviceStore.enumerateDevices();

			testAudioCtx = new AudioContext({ sampleRate: 48000 });
			const source = testAudioCtx.createMediaStreamSource(testStream);
			testAnalyser = testAudioCtx.createAnalyser();
			testAnalyser.fftSize = 256;
			testAnalyser.smoothingTimeConstant = 0.5;
			source.connect(testAnalyser);
			testActive = true;
			pollLevel();
		} catch (err) {
			console.error('Mic test failed:', err);
			toastStore.error(_t('st.could_not_access_microphone'));
		}
	}

	function pollLevel() {
		if (!testActive || !testAnalyser) return;
		const buffer = new Uint8Array(testAnalyser.frequencyBinCount);
		testAnalyser.getByteTimeDomainData(buffer);
		let sum = 0;
		for (let i = 0; i < buffer.length; i++) {
			const val = buffer[i] - 128;
			sum += val * val;
		}
		const rms = Math.sqrt(sum / buffer.length);
		testLevel = Math.min(100, Math.round(rms * 3));
		testRafId = requestAnimationFrame(pollLevel);
	}

	function stopMicTest() {
		testActive = false;
		cancelAnimationFrame(testRafId);
		testStream?.getTracks().forEach(t => t.stop());
		testStream = null;
		testAudioCtx?.close();
		testAudioCtx = null;
		testAnalyser = null;
		testLevel = 0;
	}

	// Clean up mic test and stale dialogs when switching tabs or unmounting
	$effect(() => {
		if (activeTab !== 'voice') stopMicTest();
		settingsConfirmDialog = null;
		return () => stopMicTest();
	});

	// Enumerate devices on mount
	onMount(() => { audioDeviceStore.enumerateDevices(); });
	onDestroy(() => {
		stopMicTest();
		cancelRecordingKey();
		if (profileMsgTimer) clearTimeout(profileMsgTimer);
	});

	const nsLevels: { id: NoiseSuppression; label: string; desc: string; cpu: string }[] = [
		{ id: 'off', label: 'Off', desc: 'No noise processing', cpu: '' },
		{ id: 'noise-gate', label: 'Noise Gate', desc: 'Silences audio below a volume threshold', cpu: 'Minimal CPU' },
		{ id: 'standard', label: 'Standard', desc: 'DSP-based noise reduction (Speex)', cpu: 'Low CPU' },
		{ id: 'maximum', label: 'Maximum', desc: 'ML-powered noise removal (RNNoise)', cpu: 'Moderate CPU' }
	];

	const accentColorList: { id: AccentColor; label: string }[] = [
		{ id: 'blue', label: 'Blue' },
		{ id: 'purple', label: 'Purple' },
		{ id: 'green', label: 'Green' },
		{ id: 'orange', label: 'Orange' },
		{ id: 'red', label: 'Red' },
		{ id: 'pink', label: 'Pink' },
		{ id: 'teal', label: 'Teal' },
		{ id: 'cyan', label: 'Cyan' },
	];

	const presetThemeList = (Object.keys(PRESET_THEMES) as PresetTheme[]).filter(k => k !== 'custom');

	onMount(async () => {
		if (!authStore.isAuthenticated) {
			goto('/login');
			return;
		}
		await loadSessions();
	});

	async function loadSessions() {
		sessionsLoading = true;
		sessionsError = '';
		try {
			sessions = await listSessions();
		} catch (err) {
			sessionsError = err instanceof Error ? err.message : 'Failed to load sessions';
		} finally {
			sessionsLoading = false;
		}
	}

	function handleAvatarUpload(e: Event) {
		const input = e.target as HTMLInputElement;
		const file = input.files?.[0];
		if (!file) return;
		if (avatarInputEl) avatarInputEl.value = '';
		// GIFs lose animation when drawn to canvas — skip cropper
		if (file.type === 'image/gif') {
			cropTarget = 'avatar';
			uploadCroppedImage(file);
			return;
		}
		cropFile = file;
		cropTarget = 'avatar';
	}

	async function uploadCroppedImage(blob: Blob) {
		const target = cropTarget;
		cropFile = null;
		cropTarget = null;

		if (target === 'avatar') {
			avatarUploading = true;
			profileError = '';
			try {
				const updated = await uploadAvatar(blob);
				authStore.updateUser(updated);
				userStore.setUser(updated);
				setProfileMessage('Avatar updated.');
			} catch (err) {
				profileError = err instanceof Error ? err.message : 'Failed to upload avatar';
			} finally {
				avatarUploading = false;
			}
		} else if (target === 'banner') {
			bannerUploading = true;
			profileError = '';
			try {
				const updated = await uploadBanner(blob);
				authStore.updateUser(updated);
				userStore.setUser(updated);
				setProfileMessage('Banner updated.');
			} catch (err) {
				profileError = err instanceof Error ? err.message : 'Failed to upload banner';
			} finally {
				bannerUploading = false;
			}
		} else if (target === 'voiceBg') {
			voiceBgUploading = true;
			try {
				const result = await uploadVoiceBackground(blob);
				voiceBgCustomUrl = result.url;
				voiceBgType = 'custom';
				applyVoiceBg();
				toastStore.success(_t('st.background_uploaded'));
			} catch (err: any) {
				toastStore.error(err?.message ?? 'Upload failed');
			} finally {
				voiceBgUploading = false;
			}
		}
	}

	function cancelCrop() {
		cropFile = null;
		cropTarget = null;
	}

	async function handleRemoveAvatar() {
		profileSaving = true;
		profileError = '';
		try {
			const updated = await updateProfile({ avatar_url: '' });
			authStore.updateUser(updated);
			userStore.setUser(updated);
			setProfileMessage('Avatar removed.');
		} catch (err) {
			profileError = err instanceof Error ? err.message : 'Failed to remove avatar';
		} finally {
			profileSaving = false;
		}
	}

	function handleBannerUpload(e: Event) {
		const input = e.target as HTMLInputElement;
		const file = input.files?.[0];
		if (!file) return;
		if (bannerInputEl) bannerInputEl.value = '';
		if (file.type === 'image/gif') {
			cropTarget = 'banner';
			uploadCroppedImage(file);
			return;
		}
		cropFile = file;
		cropTarget = 'banner';
	}

	async function handleRemoveBanner() {
		profileSaving = true;
		profileError = '';
		try {
			const updated = await updateProfile({ banner_url: '' });
			authStore.updateUser(updated);
			userStore.setUser(updated);
			setProfileMessage('Banner removed.');
		} catch (err) {
			profileError = err instanceof Error ? err.message : 'Failed to remove banner';
		} finally {
			profileSaving = false;
		}
	}

	async function handleProfileSave() {
		profileSaving = true;
		profileError = '';
		profileMessage = '';
		try {
			const updated = await updateProfile({
				display_name: editDisplayName || undefined,
				custom_status: editCustomStatus || undefined,
				bio: editBio || null,
				pronouns: editPronouns || null
			});
			authStore.updateUser(updated);
			userStore.setUser(updated);
			setProfileMessage('Profile updated.');
		} catch (err) {
			profileError = err instanceof Error ? err.message : 'Failed to update profile';
		} finally {
			profileSaving = false;
		}
	}

	async function handleChangePassword(e: SubmitEvent) {
		e.preventDefault();
		passwordError = '';

		if (newPassword !== confirmPassword) {
			passwordError = 'Passwords do not match.';
			return;
		}
		if (!pwAllMet) {
			passwordError = 'Password does not meet all requirements.';
			return;
		}

		passwordSaving = true;
		try {
			await changePassword(currentPassword, newPassword);
			authStore.logout();
			goto('/login');
		} catch (err) {
			passwordError = err instanceof Error ? err.message : 'Failed to change password';
		} finally {
			passwordSaving = false;
		}
	}

	async function handleRevokeSession(id: string) {
		if (revokingSessionId) return;
		revokingSessionId = id;
		try {
			await revokeSession(id);
			sessions = sessions.filter(s => s.id !== id);
		} catch (err) {
			toastStore.error(err instanceof Error ? err.message : 'Failed to revoke session');
		} finally {
			revokingSessionId = null;
		}
	}

	function handleLogoutAll() {
		if (revokingAll) return;
		settingsConfirmDialog = {
			title: 'Revoke all sessions?',
			message: 'This will log you out from all devices and browsers, including this one.',
			confirmLabel: 'Revoke All',
			danger: true,
			async onConfirm() {
				revokingAll = true;
				try {
					await logoutAll();
					authStore.logout();
					goto('/login');
				} catch (err) {
					toastStore.error(err instanceof Error ? err.message : 'Failed to logout');
				} finally {
					revokingAll = false;
				}
			}
		};
	}

	async function handleDeleteAccount(e: SubmitEvent) {
		e.preventDefault();
		deleteError = '';
		deleting = true;
		try {
			await deleteAccount(deletePassword);
			authStore.logout();
			goto('/login');
		} catch (err) {
			deleteError = err instanceof Error ? err.message : 'Failed to delete account';
		} finally {
			deleting = false;
		}
	}

	async function handleSetupTotp() {
		totpError = '';
		totpMessage = '';
		try {
			totpSetup = await setupTotp();
			totpQrDataUrl = await QRCode.toDataURL(totpSetup.otpauth_url, {
				width: 200,
				margin: 2,
				color: { dark: '#000000', light: '#ffffff' }
			});
			showTotpSetup = true;
		} catch (err) {
			totpError = err instanceof Error ? err.message : 'Failed to setup 2FA';
		}
	}

	async function handleVerifyTotp(e: SubmitEvent) {
		e.preventDefault();
		totpError = '';
		try {
			const result = await verifyTotp(totpCode);
			totpMessage = '2FA enabled successfully!';
			showTotpSetup = false;
			totpSetup = null;
			totpCode = '';
			if (result.backup_codes?.length) {
				backupCodes = result.backup_codes;
				showBackupCodes = true;
			}
		} catch (err) {
			totpError = err instanceof Error ? err.message : 'Invalid code';
		}
	}

	async function handleDisableTotp(e: SubmitEvent) {
		e.preventDefault();
		totpError = '';
		try {
			await disableTotp(disableCode);
			totpMessage = '2FA disabled.';
			showTotpDisable = false;
			disableCode = '';
			backupCodes = [];
			showBackupCodes = false;
		} catch (err) {
			totpError = err instanceof Error ? err.message : 'Invalid code';
		}
	}

	async function handleRegenerateRecoveryCode() {
		recoveryLoading = true;
		recoveryError = '';
		try {
			const result = await regenerateRecoveryCode();
			recoveryCode = result.recovery_code;
			showRecoveryCode = true;
		} catch (err) {
			recoveryError = err instanceof Error ? err.message : 'Failed to regenerate recovery code';
		} finally {
			recoveryLoading = false;
		}
	}

	async function handleRegenerateBackupCodes(e: SubmitEvent) {
		e.preventDefault();
		backupCodeError = '';
		try {
			const result = await regenerateBackupCodes(backupCodeTotpInput);
			backupCodes = result.backup_codes;
			showBackupCodes = true;
			backupCodeTotpInput = '';
		} catch (err) {
			backupCodeError = err instanceof Error ? err.message : 'Invalid TOTP code';
		}
	}
</script>

{#if authStore.isAuthenticated}
	<div class="flex min-h-screen bg-[var(--bg-primary)] text-[var(--text-primary)]">
		<!-- Sidebar navigation (hidden on mobile) -->
		<nav class="hidden md:flex w-56 shrink-0 flex-col border-r border-white/10 bg-[var(--bg-secondary)] p-4">
			<div class="mb-6 flex items-center justify-between">
				<h1 class="text-lg font-bold">{_t('st.settings')}</h1>
				<button
					onclick={() => goto('/channels')}
					class="rounded-lg p-1.5 text-[var(--text-secondary)] transition hover:bg-white/5 hover:text-[var(--text-primary)]"
					title={_t('st.back_to_chat')}
					aria-label={_t('st.back_to_chat')}
				>
					<svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
						<line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
					</svg>
				</button>
			</div>

			<div class="flex flex-col gap-1" role="tablist" aria-label={_t('st.settings')}>
				{#each tabs as tab}
					<button
						onclick={() => (activeTab = tab.id)}
						role="tab"
						aria-selected={activeTab === tab.id}
						class="flex items-center gap-3 rounded-lg px-3 py-2 text-left text-sm transition
							{activeTab === tab.id
								? 'bg-[var(--accent)]/15 text-[var(--accent)] font-medium'
								: 'text-[var(--text-secondary)] hover:bg-white/5 hover:text-[var(--text-primary)]'}"
					>
						<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
							<path d={tab.icon} />
						</svg>
						{tab.label}
					</button>
				{/each}
			</div>

			<div class="mt-auto pt-4">
				{#if authStore.user?.is_admin || authStore.user?.is_owner}
					<button
						onclick={() => goto('/admin')}
						class="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left text-sm text-[var(--accent)] transition hover:bg-white/5"
					>
						<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
							<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
						</svg>
						{_t('st.admin_panel')}
					</button>
				{/if}
			</div>
		</nav>

		<!-- Content area -->
		<div class="flex-1 overflow-y-auto">
			<!-- Mobile header + tab bar -->
			<div class="sticky top-0 z-10 flex items-center gap-2 border-b border-white/10 bg-[var(--bg-secondary)] px-4 py-2 md:hidden">
				<button
					onclick={() => goto('/channels')}
					class="shrink-0 rounded-lg p-1.5 text-[var(--text-secondary)] transition hover:bg-white/5"
					title={_t('st.back_to_chat')}
					aria-label={_t('st.back_to_chat')}
				>
					<svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
						<polyline points="15 18 9 12 15 6" />
					</svg>
				</button>
				<div class="flex flex-1 gap-1 overflow-x-auto" role="tablist" aria-label={_t('st.settings')}>
					{#each tabs as tab}
						<button
							onclick={() => (activeTab = tab.id)}
							role="tab"
							aria-selected={activeTab === tab.id}
							class="shrink-0 rounded-lg px-2.5 py-1.5 text-xs font-medium transition
								{activeTab === tab.id
									? 'bg-[var(--accent)]/15 text-[var(--accent)]'
									: 'text-[var(--text-secondary)] hover:bg-white/5'}"
						>
							{tab.label}
						</button>
					{/each}
				</div>
			</div>
			<div class="mx-auto max-w-2xl px-4 py-4 sm:px-6 sm:py-6 md:px-8 md:py-8">

				<!-- ══════════════════ PROFILE TAB ══════════════════ -->
				{#if activeTab === 'profile'}
					<h2 class="mb-6 text-xl font-bold">{_t('st.profile')}</h2>

					{#if profileMessage}
						<div class="mb-4 rounded-lg border border-green-500/20 bg-green-500/10 px-4 py-3 text-sm text-green-400">
							{profileMessage}
						</div>
					{/if}
					{#if profileError}
						<div class="mb-4 rounded-lg border border-red-500/20 bg-red-500/10 px-4 py-3 text-sm text-red-400">
							{profileError}
						</div>
					{/if}

					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.avatar')}</h3>
						<div class="flex items-center gap-4">
							<div class="group relative">
								{#if authStore.user}
									<Avatar userId={authStore.user.id} size="lg" />
								{/if}
								<button
									onclick={() => avatarInputEl?.click()}
									disabled={avatarUploading}
									class="absolute inset-0 flex items-center justify-center rounded-full bg-black/50 opacity-0 transition group-hover:opacity-100 disabled:cursor-wait"
									title={_t('st.change_avatar')}
									aria-label={_t('st.change_avatar')}
								>
									<svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
										<path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" /><circle cx="12" cy="13" r="4" />
									</svg>
								</button>
								<input
									bind:this={avatarInputEl}
									type="file"
									accept="image/png,image/jpeg,image/webp,image/gif"
									onchange={handleAvatarUpload}
									class="hidden"
								/>
							</div>
							<div>
								<div class="text-sm text-[var(--text-secondary)]">@{authStore.user?.username}</div>
								{#if authStore.user?.avatar_url}
									<button
										onclick={handleRemoveAvatar}
										class="mt-1 text-xs text-[var(--danger)] hover:underline"
									>
										{_t('st.remove_avatar')}
									</button>
								{/if}
							</div>
						</div>
					</section>

					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.profile_banner')}</h3>
						<div class="group relative aspect-[3/1] w-full overflow-hidden rounded-lg border border-white/10">
							{#if authStore.user?.banner_url}
								<img
									src={authStore.user.banner_url}
									alt={_t('st.profile_banner_20a8')}
									class="h-full w-full object-cover"
								/>
							{:else}
								<div class="flex h-full w-full items-center justify-center bg-gradient-to-r from-[var(--accent)] to-[var(--accent-hover)]">
									<span class="text-sm text-white/50">{_t('st.no_banner_set')}</span>
								</div>
							{/if}
							<button
								onclick={() => bannerInputEl?.click()}
								disabled={bannerUploading}
								class="absolute inset-0 flex items-center justify-center bg-black/50 opacity-0 transition group-hover:opacity-100 disabled:cursor-wait"
								title={_t('st.change_banner')}
								aria-label={_t('st.change_banner')}
							>
								<svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
									<path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" /><circle cx="12" cy="13" r="4" />
								</svg>
							</button>
							<input
								bind:this={bannerInputEl}
								type="file"
								accept="image/png,image/jpeg,image/webp,image/gif"
								onchange={handleBannerUpload}
								class="hidden"
							/>
						</div>
						<div class="mt-2 flex items-center justify-between">
							<span class="text-xs text-[var(--text-secondary)]">{_t('st.recommended_1200x400_max_10_mb')}</span>
							{#if authStore.user?.banner_url}
								<button
									onclick={handleRemoveBanner}
									class="text-xs text-[var(--danger)] hover:underline"
								>
									{_t('st.remove_banner')}
								</button>
							{/if}
						</div>
					</section>

					<section class="rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.personal_info')}</h3>
						<div class="space-y-4">
							<div>
								<label for="displayName" class="mb-1 block text-sm font-medium">{_t('st.display_name')}</label>
								<input
									id="displayName"
									type="text"
									bind:value={editDisplayName}
									maxlength="64"
									class="w-full rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] outline-none focus:border-[var(--accent)]"
								/>
								<p class="mt-1 text-right text-[10px] text-[var(--text-secondary)]/50">{editDisplayName.length}/64</p>
							</div>
							<div>
								<label for="customStatus" class="mb-1 block text-sm font-medium">{_t('st.custom_status')}</label>
								<input
									id="customStatus"
									type="text"
									bind:value={editCustomStatus}
									maxlength="128"
									placeholder={_t('st.what_s_on_your_mind')}
									class="w-full rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] outline-none focus:border-[var(--accent)]"
								/>
								<p class="mt-1 text-right text-[10px] text-[var(--text-secondary)]/50">{editCustomStatus.length}/128</p>
							</div>
							<div>
								<label for="pronouns" class="mb-1 block text-sm font-medium">{_t('st.pronouns')}</label>
								<input
									id="pronouns"
									type="text"
									bind:value={editPronouns}
									maxlength="50"
									placeholder="e.g. they/them"
									class="w-full rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] outline-none focus:border-[var(--accent)]"
								/>
								<p class="mt-1 text-right text-[10px] text-[var(--text-secondary)]/50">{editPronouns.length}/50</p>
							</div>
							<div>
								<label for="bio" class="mb-1 block text-sm font-medium">{_t('st.bio')}</label>
								<textarea
									id="bio"
									bind:value={editBio}
									maxlength="500"
									rows="3"
									placeholder={_t('st.tell_others_about_yourself')}
									class="w-full resize-none rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] outline-none focus:border-[var(--accent)]"
								></textarea>
								<p class="mt-1 text-right text-[10px] text-[var(--text-secondary)]/50">{editBio.length}/500</p>
							</div>
							<button
								onclick={handleProfileSave}
								disabled={profileSaving}
								class="rounded-lg bg-[var(--accent)] px-4 py-2 text-sm font-medium text-white transition hover:bg-[var(--accent-hover)] disabled:opacity-50"
							>
								{profileSaving ? 'Saving...' : 'Save Profile'}
							</button>
						</div>
					</section>

				<!-- ══════════════════ APPEARANCE TAB ══════════════════ -->
				{:else if activeTab === 'appearance'}
					<h2 class="mb-6 text-xl font-bold">{_t('st.appearance')}</h2>

					<!-- Language -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{t('settings.language')}</h3>
						<div class="flex flex-wrap items-center justify-between gap-3">
							<div class="text-sm text-[var(--text-secondary)]">{t('settings.languageHint')}</div>
							<div class="flex overflow-hidden rounded-lg border border-white/10" role="group" aria-label={t('settings.language')}>
								{#each LOCALES as loc (loc.code)}
									<button
										onclick={() => i18n.set(loc.code)}
										aria-pressed={i18n.locale === loc.code}
										class="px-4 py-2 text-sm transition {i18n.locale === loc.code ? 'bg-[var(--accent)] text-white' : 'text-[var(--text-secondary)] hover:bg-white/5'}"
									>
										{loc.label}
									</button>
								{/each}
							</div>
						</div>
					</section>

					<!-- Theme -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.theme')}</h3>
						<div class="flex items-center justify-between">
							<div>
								<div class="font-medium">{_t('st.color_scheme')}</div>
								<div class="text-sm text-[var(--text-secondary)]">
									{themeStore.current === 'system' ? `System (${themeStore.resolved === 'dark' ? 'Dark' : 'Light'})` : themeStore.resolved === 'dark' ? 'Dark mode' : 'Light mode'}
								</div>
							</div>
							<div class="flex overflow-hidden rounded-lg border border-white/10">
								<button
									onclick={() => preferencesStore.set('theme', 'dark')}
									class="px-3 py-1.5 text-sm transition {themeStore.current === 'dark' ? 'bg-[var(--accent)] text-white' : 'text-[var(--text-secondary)] hover:bg-white/5'}"
									title={_t('st.dark')}
									aria-label={_t('st.dark_theme')}
								>
									<svg xmlns="http://www.w3.org/2000/svg" class="inline h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
										<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
									</svg>
								</button>
								<button
									onclick={() => preferencesStore.set('theme', 'light')}
									class="border-x border-white/10 px-3 py-1.5 text-sm transition {themeStore.current === 'light' ? 'bg-[var(--accent)] text-white' : 'text-[var(--text-secondary)] hover:bg-white/5'}"
									title={_t('st.light')}
									aria-label={_t('st.light_theme')}
								>
									<svg xmlns="http://www.w3.org/2000/svg" class="inline h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
										<circle cx="12" cy="12" r="5" /><line x1="12" y1="1" x2="12" y2="3" /><line x1="12" y1="21" x2="12" y2="23" /><line x1="4.22" y1="4.22" x2="5.64" y2="5.64" /><line x1="18.36" y1="18.36" x2="19.78" y2="19.78" /><line x1="1" y1="12" x2="3" y2="12" /><line x1="21" y1="12" x2="23" y2="12" /><line x1="4.22" y1="19.78" x2="5.64" y2="18.36" /><line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
									</svg>
								</button>
								<button
									onclick={() => preferencesStore.set('theme', 'system')}
									class="px-3 py-1.5 text-sm transition {themeStore.current === 'system' ? 'bg-[var(--accent)] text-white' : 'text-[var(--text-secondary)] hover:bg-white/5'}"
									title={_t('st.system')}
									aria-label={_t('st.system_theme')}
								>
									<svg xmlns="http://www.w3.org/2000/svg" class="inline h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
										<rect x="2" y="3" width="20" height="14" rx="2" ry="2" /><line x1="8" y1="21" x2="16" y2="21" /><line x1="12" y1="17" x2="12" y2="21" />
									</svg>
								</button>
							</div>
						</div>
					</section>

					<!-- Preset Themes -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.color_palette')}</h3>
						<div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-2">
							{#each presetThemeList as themeId}
								{@const t = PRESET_THEMES[themeId]}
								<button
									onclick={() => preferencesStore.set('presetTheme', themeId)}
									class="group flex flex-col items-center gap-1.5 rounded-lg border p-2 transition
										{preferencesStore.preferences.presetTheme === themeId
											? 'border-[var(--accent)] bg-[var(--accent)]/10'
											: 'border-white/10 hover:border-white/20'}"
								>
									<div class="flex h-8 w-full overflow-hidden rounded">
										<div class="flex-1" style="background: {t.colors.dark.bgPrimary}"></div>
										<div class="flex-1" style="background: {t.colors.dark.bgTertiary}"></div>
										<div class="flex-1" style="background: {t.colors.dark.accent}"></div>
									</div>
									<span class="text-xs text-[var(--text-secondary)]">{t.label}</span>
								</button>
							{/each}
							<!-- Custom theme button -->
							<button
								onclick={() => preferencesStore.set('presetTheme', 'custom')}
								class="group flex flex-col items-center gap-1.5 rounded-lg border p-2 transition
									{preferencesStore.preferences.presetTheme === 'custom'
										? 'border-[var(--accent)] bg-[var(--accent)]/10'
										: 'border-white/10 hover:border-white/20'}"
							>
								<div class="flex h-8 w-full items-center justify-center rounded bg-white/5">
									<svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
										<circle cx="13.5" cy="6.5" r="2.5" /><circle cx="19" cy="11.5" r="2.5" /><circle cx="6" cy="12.5" r="2.5" /><circle cx="17" cy="18.5" r="2.5" /><circle cx="8.5" cy="18.5" r="2.5" />
									</svg>
								</div>
								<span class="text-xs text-[var(--text-secondary)]">{_t('st.custom')}</span>
							</button>
						</div>

						<!-- Custom theme color pickers -->
						{#if preferencesStore.preferences.presetTheme === 'custom'}
							<div class="mt-4 grid grid-cols-2 gap-3 border-t border-white/10 pt-4">
								{#each [
									{ key: 'bgPrimary', label: 'Background' },
									{ key: 'bgSecondary', label: 'Surface' },
									{ key: 'bgTertiary', label: 'Elevated' },
									{ key: 'textPrimary', label: 'Text' },
									{ key: 'textSecondary', label: 'Muted text' },
									{ key: 'accent', label: 'Accent' },
									{ key: 'accentHover', label: 'Accent hover' }
								] as field}
									<label class="flex items-center gap-2">
										<input
											type="color"
											value={preferencesStore.preferences.customThemeColors[field.key as keyof typeof preferencesStore.preferences.customThemeColors]}
											oninput={(e) => {
												const updated = { ...preferencesStore.preferences.customThemeColors, [field.key]: (e.target as HTMLInputElement).value };
												preferencesStore.set('customThemeColors', updated);
											}}
											class="h-8 w-8 cursor-pointer rounded border border-white/10 bg-transparent"
										/>
										<span class="text-sm text-[var(--text-secondary)]">{field.label}</span>
									</label>
								{/each}
							</div>
						{/if}
					</section>

					<!-- Accent Color (only shown for default theme) -->
					{#if preferencesStore.preferences.presetTheme === 'default'}
						<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
							<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.accent_color')}</h3>
							<div class="flex flex-wrap gap-3">
								{#each accentColorList as color}
									<button
										onclick={() => preferencesStore.set('accentColor', color.id)}
										class="group flex flex-col items-center gap-1.5"
										title={color.label}
									>
										<div
											class="flex h-10 w-10 items-center justify-center rounded-full transition-transform hover:scale-110
												{preferencesStore.preferences.accentColor === color.id ? 'ring-2 ring-white ring-offset-2 ring-offset-[var(--bg-secondary)]' : ''}"
											style="background-color: {ACCENT_COLORS[color.id].main};"
										>
											{#if preferencesStore.preferences.accentColor === color.id}
												<svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
													<polyline points="20 6 9 17 4 12" />
												</svg>
											{/if}
										</div>
										<span class="text-xs text-[var(--text-secondary)]">{color.label}</span>
									</button>
								{/each}
							</div>
						</section>
					{/if}

					<!-- Message Density -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.message_display')}</h3>

						<div class="mb-4">
							<div class="mb-2 font-medium">{_t('st.density')}</div>
							<div class="flex gap-3">
								<button
									onclick={() => preferencesStore.set('messageDensity', 'cozy')}
									class="flex-1 rounded-lg border p-3 text-left transition
										{preferencesStore.preferences.messageDensity === 'cozy'
											? 'border-[var(--accent)] bg-[var(--accent)]/10'
											: 'border-white/10 hover:border-white/20'}"
								>
									<div class="mb-1 text-sm font-medium">{_t('st.cozy')}</div>
									<div class="text-xs text-[var(--text-secondary)]">{_t('st.avatars_and_spacious_layout')}</div>
								</button>
								<button
									onclick={() => preferencesStore.set('messageDensity', 'compact')}
									class="flex-1 rounded-lg border p-3 text-left transition
										{preferencesStore.preferences.messageDensity === 'compact'
											? 'border-[var(--accent)] bg-[var(--accent)]/10'
											: 'border-white/10 hover:border-white/20'}"
								>
									<div class="mb-1 text-sm font-medium">{_t('st.compact')}</div>
									<div class="text-xs text-[var(--text-secondary)]">{_t('st.no_avatars_tighter_spacing')}</div>
								</button>
							</div>
						</div>

						<!-- Chat Bubble Style -->
						<div class="mb-4">
							<div class="mb-2 font-medium">{_t('st.bubble_style')}</div>
							<div class="flex gap-3">
								<button
									onclick={() => preferencesStore.set('chatBubbleStyle', 'flat')}
									class="flex-1 rounded-lg border p-3 text-left transition
										{preferencesStore.preferences.chatBubbleStyle === 'flat'
											? 'border-[var(--accent)] bg-[var(--accent)]/10'
											: 'border-white/10 hover:border-white/20'}"
								>
									<div class="mb-1 text-sm font-medium">{_t('st.flat')}</div>
									<div class="text-xs text-[var(--text-secondary)]">{_t('st.flat_style_with_color_border')}</div>
								</button>
								<button
									onclick={() => preferencesStore.set('chatBubbleStyle', 'bubbles')}
									class="flex-1 rounded-lg border p-3 text-left transition
										{preferencesStore.preferences.chatBubbleStyle === 'bubbles'
											? 'border-[var(--accent)] bg-[var(--accent)]/10'
											: 'border-white/10 hover:border-white/20'}"
								>
									<div class="mb-1 text-sm font-medium">{_t('st.bubbles')}</div>
									<div class="text-xs text-[var(--text-secondary)]">{_t('st.imessage_style_rounded_bubbles')}</div>
								</button>
							</div>
						</div>

						<!-- Sidebar Layout -->
						<div class="mb-4">
							<div class="mb-2 font-medium">{_t('st.sidebar_layout')}</div>
							<div class="flex gap-3">
								<button
									onclick={() => preferencesStore.set('sidebarLayout', 'expanded')}
									class="flex-1 rounded-lg border p-3 text-left transition
										{preferencesStore.preferences.sidebarLayout === 'expanded'
											? 'border-[var(--accent)] bg-[var(--accent)]/10'
											: 'border-white/10 hover:border-white/20'}"
								>
									<div class="mb-1 text-sm font-medium">{_t('st.expanded')}</div>
									<div class="text-xs text-[var(--text-secondary)]">{_t('st.always_visible_sidebar_panel')}</div>
								</button>
								<button
									onclick={() => preferencesStore.set('sidebarLayout', 'compact')}
									class="flex-1 rounded-lg border p-3 text-left transition
										{preferencesStore.preferences.sidebarLayout === 'compact'
											? 'border-[var(--accent)] bg-[var(--accent)]/10'
											: 'border-white/10 hover:border-white/20'}"
								>
									<div class="mb-1 text-sm font-medium">{_t('st.compact')}</div>
									<div class="text-xs text-[var(--text-secondary)]">{_t('st.dropdown_navigation_overlay')}</div>
								</button>
							</div>
						</div>

						<!-- Time Format -->
						<div class="mb-4 flex items-center justify-between">
							<div>
								<div class="font-medium">{_t('st.time_format')}</div>
								<div class="text-sm text-[var(--text-secondary)]">{_t('st.how_timestamps_are_displayed')}</div>
							</div>
							<div class="flex overflow-hidden rounded-lg border border-white/10">
								<button
									onclick={() => preferencesStore.set('timeFormat', '12h')}
									class="px-3 py-1.5 text-sm transition {preferencesStore.preferences.timeFormat === '12h' ? 'bg-[var(--accent)] text-white' : 'text-[var(--text-secondary)] hover:bg-white/5'}"
								>
									12h
								</button>
								<button
									onclick={() => preferencesStore.set('timeFormat', '24h')}
									class="border-l border-white/10 px-3 py-1.5 text-sm transition {preferencesStore.preferences.timeFormat === '24h' ? 'bg-[var(--accent)] text-white' : 'text-[var(--text-secondary)] hover:bg-white/5'}"
								>
									24h
								</button>
							</div>
						</div>

						<!-- Relative Timestamps -->
						<div class="mb-4 flex items-center justify-between">
							<div>
								<div class="font-medium">{_t('st.relative_timestamps')}</div>
								<div class="text-sm text-[var(--text-secondary)]">Show "5m ago" instead of exact time</div>
							</div>
							<button
								onclick={() => preferencesStore.set('relativeTimestamps', !preferencesStore.preferences.relativeTimestamps)}
								class="relative h-8 w-14 rounded-full bg-[var(--bg-tertiary)] transition focus-visible:ring-2 focus-visible:ring-[var(--accent)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-secondary)]"
								aria-label={_t('st.toggle_relative_timestamps')}
								role="switch"
								aria-checked={preferencesStore.preferences.relativeTimestamps}
							>
								<span class="absolute left-1 top-1 h-6 w-6 rounded-full transition-transform {preferencesStore.preferences.relativeTimestamps ? 'translate-x-6 bg-[var(--accent)]' : 'bg-[var(--text-secondary)]'}"></span>
							</button>
						</div>

						<!-- UI Zoom -->
						<div class="mb-4 flex items-center justify-between">
							<div>
								<div class="font-medium">{_t('st.ui_zoom')}</div>
								<div class="text-sm text-[var(--text-secondary)]">{preferencesStore.preferences.uiZoom}%</div>
							</div>
							<div class="flex items-center gap-2">
								<div class="flex overflow-hidden rounded-lg border border-white/10">
									{#each [75, 90, 100, 110, 125, 150] as zoom}
										<button
											onclick={() => preferencesStore.set('uiZoom', zoom)}
											class="px-2.5 py-1.5 text-xs transition {zoom !== 75 ? 'border-l border-white/10' : ''}
												{preferencesStore.preferences.uiZoom === zoom ? 'bg-[var(--accent)] text-white' : 'text-[var(--text-secondary)] hover:bg-white/5'}"
										>
											{zoom}%
										</button>
									{/each}
								</div>
								{#if preferencesStore.preferences.uiZoom !== 100}
									<button
										onclick={() => preferencesStore.set('uiZoom', 100)}
										class="rounded-lg px-2 py-1.5 text-xs text-[var(--text-secondary)] hover:bg-white/5 hover:text-[var(--text-primary)] transition"
										title={_t('st.reset_to_100')}
									>
										{_t('st.reset')}
									</button>
								{/if}
							</div>
						</div>

						<!-- Font Size -->
						<div class="flex items-center justify-between">
							<div>
								<div class="font-medium">{_t('st.font_size')}</div>
								<div class="text-sm text-[var(--text-secondary)]">{FONT_SIZES[preferencesStore.preferences.fontSize]}</div>
							</div>
							<div class="flex overflow-hidden rounded-lg border border-white/10">
								{#each (['small', 'medium', 'large'] as const) as size}
									<button
										onclick={() => preferencesStore.set('fontSize', size)}
										class="px-3 py-1.5 text-sm transition {size !== 'small' ? 'border-l border-white/10' : ''}
											{preferencesStore.preferences.fontSize === size ? 'bg-[var(--accent)] text-white' : 'text-[var(--text-secondary)] hover:bg-white/5'}"
									>
										{size.charAt(0).toUpperCase() + size.slice(1)}
									</button>
								{/each}
							</div>
						</div>
					</section>

					<!-- Accessibility -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.accessibility')}</h3>

						<!-- Reduce Motion -->
						<div class="mb-4 flex items-center justify-between">
							<div>
								<div class="font-medium">{_t('st.reduce_motion')}</div>
								<div class="text-sm text-[var(--text-secondary)]">{_t('st.disable_animations_and_transitions')}</div>
							</div>
							<button
								onclick={() => preferencesStore.set('reduceMotion', !preferencesStore.preferences.reduceMotion)}
								class="relative h-8 w-14 rounded-full bg-[var(--bg-tertiary)] transition focus-visible:ring-2 focus-visible:ring-[var(--accent)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-secondary)]"
								aria-label={_t('st.toggle_reduce_motion')}
								role="switch"
								aria-checked={preferencesStore.preferences.reduceMotion}
							>
								<span class="absolute left-1 top-1 h-6 w-6 rounded-full transition-transform {preferencesStore.preferences.reduceMotion ? 'translate-x-6 bg-[var(--accent)]' : 'bg-[var(--text-secondary)]'}"></span>
							</button>
						</div>

						<!-- Animated Accent -->
						<div class="flex items-center justify-between">
							<div>
								<div class="font-medium">{_t('st.animated_accent')}</div>
								<div class="text-sm text-[var(--text-secondary)]">{_t('st.subtle_color_shift_on_accent_element')}</div>
							</div>
							<button
								onclick={() => preferencesStore.set('animatedAccent', !preferencesStore.preferences.animatedAccent)}
								class="relative h-8 w-14 rounded-full bg-[var(--bg-tertiary)] transition focus-visible:ring-2 focus-visible:ring-[var(--accent)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-secondary)]"
								aria-label={_t('st.toggle_animated_accent')}
								role="switch"
								aria-checked={preferencesStore.preferences.animatedAccent}
							>
								<span class="absolute left-1 top-1 h-6 w-6 rounded-full transition-transform {preferencesStore.preferences.animatedAccent ? 'translate-x-6 bg-[var(--accent)]' : 'bg-[var(--text-secondary)]'}"></span>
							</button>
						</div>
					</section>

				<!-- ══════════════════ NOTIFICATIONS TAB ══════════════════ -->
				{:else if activeTab === 'notifications'}
					<h2 class="mb-6 text-xl font-bold">{_t('st.notifications')}</h2>

					<!-- Sounds -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.sounds')}</h3>
						<div class="space-y-4">
							<div class="flex items-center justify-between">
								<div>
									<div class="font-medium">{_t('st.dm_message_sound')}</div>
									<div class="text-sm text-[var(--text-secondary)]">{_t('st.play_a_sound_for_direct_messages')}</div>
								</div>
								<div class="flex items-center gap-2">
									<button
										onclick={() => soundStore.playDmNotification()}
										class="rounded px-2 py-1 text-xs text-[var(--accent)] transition hover:bg-white/5"
									>Test</button>
									<button
										onclick={() => { soundStore.preferences.dmMessage = !soundStore.preferences.dmMessage; soundStore.save(); }}
										class="relative h-8 w-14 rounded-full bg-[var(--bg-tertiary)] transition focus-visible:ring-2 focus-visible:ring-[var(--accent)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-secondary)]"
										aria-label={_t('st.toggle_dm_sound')}
										role="switch"
										aria-checked={soundStore.preferences.dmMessage}
									>
										<span class="absolute left-1 top-1 h-6 w-6 rounded-full transition-transform {soundStore.preferences.dmMessage ? 'translate-x-6 bg-[var(--accent)]' : 'bg-[var(--text-secondary)]'}"></span>
									</button>
								</div>
							</div>

							<div class="flex items-center justify-between">
								<div>
									<div class="font-medium">{_t('st.channel_message_sound')}</div>
									<div class="text-sm text-[var(--text-secondary)]">{_t('st.play_a_sound_for_channel_messages')}</div>
								</div>
								<div class="flex items-center gap-2">
									<button
										onclick={() => soundStore.playChannelNotification()}
										class="rounded px-2 py-1 text-xs text-[var(--accent)] transition hover:bg-white/5"
									>Test</button>
									<button
										onclick={() => { soundStore.preferences.channelMessage = !soundStore.preferences.channelMessage; soundStore.save(); }}
										class="relative h-8 w-14 rounded-full bg-[var(--bg-tertiary)] transition focus-visible:ring-2 focus-visible:ring-[var(--accent)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-secondary)]"
										aria-label={_t('st.toggle_channel_sound')}
										role="switch"
										aria-checked={soundStore.preferences.channelMessage}
									>
										<span class="absolute left-1 top-1 h-6 w-6 rounded-full transition-transform {soundStore.preferences.channelMessage ? 'translate-x-6 bg-[var(--accent)]' : 'bg-[var(--text-secondary)]'}"></span>
									</button>
								</div>
							</div>

							<div class="flex items-center justify-between">
								<div>
									<div class="font-medium">{_t('st.mention_sound')}</div>
									<div class="text-sm text-[var(--text-secondary)]">Distinct sound when @mentioned</div>
								</div>
								<div class="flex items-center gap-2">
									<button
										onclick={() => soundStore.playMentionNotification()}
										class="rounded px-2 py-1 text-xs text-[var(--accent)] transition hover:bg-white/5"
									>Test</button>
									<button
										onclick={() => { soundStore.preferences.mentionMessage = !soundStore.preferences.mentionMessage; soundStore.save(); }}
										class="relative h-8 w-14 rounded-full bg-[var(--bg-tertiary)] transition focus-visible:ring-2 focus-visible:ring-[var(--accent)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-secondary)]"
										aria-label={_t('st.toggle_mention_sound')}
										role="switch"
										aria-checked={soundStore.preferences.mentionMessage}
									>
										<span class="absolute left-1 top-1 h-6 w-6 rounded-full transition-transform {soundStore.preferences.mentionMessage ? 'translate-x-6 bg-[var(--accent)]' : 'bg-[var(--text-secondary)]'}"></span>
									</button>
								</div>
							</div>

							<div class="flex items-center justify-between">
								<div>
									<div class="font-medium">{_t('st.voice_join_leave_sounds')}</div>
									<div class="text-sm text-[var(--text-secondary)]">{_t('st.when_someone_joins_or_leaves_voice')}</div>
								</div>
								<div class="flex items-center gap-2">
									<button
										onclick={() => soundStore.playVoiceJoin()}
										class="rounded px-2 py-1 text-xs text-[var(--accent)] transition hover:bg-white/5"
									>Test</button>
									<button
										onclick={() => { soundStore.preferences.voiceJoin = !soundStore.preferences.voiceJoin; soundStore.preferences.voiceLeave = !soundStore.preferences.voiceLeave; soundStore.save(); }}
										class="relative h-8 w-14 rounded-full bg-[var(--bg-tertiary)] transition focus-visible:ring-2 focus-visible:ring-[var(--accent)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-secondary)]"
										aria-label={_t('st.toggle_voice_sounds')}
										role="switch"
										aria-checked={soundStore.preferences.voiceJoin}
									>
										<span class="absolute left-1 top-1 h-6 w-6 rounded-full transition-transform {soundStore.preferences.voiceJoin ? 'translate-x-6 bg-[var(--accent)]' : 'bg-[var(--text-secondary)]'}"></span>
									</button>
								</div>
							</div>

							<div>
								<div class="mb-2 flex items-center justify-between">
									<div class="font-medium">{_t('st.volume')}</div>
									<span class="text-sm text-[var(--text-secondary)]">{Math.round(soundStore.preferences.volume * 100)}%</span>
								</div>
								<input
									type="range"
									min="0"
									max="1"
									step="0.05"
									bind:value={soundStore.preferences.volume}
									oninput={() => soundStore.save()}
									class="w-full accent-[var(--accent)]"
								/>
							</div>
						</div>
					</section>

					<!-- Desktop Notifications -->
					<section class="rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.desktop_notifications')}</h3>
						<div class="space-y-4">
							<div class="flex items-center justify-between">
								<div>
									<div class="font-medium">{_t('st.desktop_notifications_6029')}</div>
									<div class="text-sm text-[var(--text-secondary)]">{_t('st.show_os_level_notifications')}</div>
								</div>
								<div class="flex items-center gap-2">
									{#if notificationStore.permissionState === 'unsupported'}
										<span class="text-xs text-[var(--text-secondary)]">{_t('st.not_supported')}</span>
									{:else if notificationStore.permissionState === 'denied'}
										<span class="text-xs text-red-400">{_t('st.blocked_by_browser')}</span>
									{:else}
										<button
											onclick={async () => {
												if (notificationStore.preferences.desktopEnabled) {
													notificationStore.preferences = { ...notificationStore.preferences, desktopEnabled: false };
													notificationStore.save();
												} else {
													await notificationStore.requestPermission();
												}
											}}
											class="relative h-8 w-14 rounded-full bg-[var(--bg-tertiary)] transition focus-visible:ring-2 focus-visible:ring-[var(--accent)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-secondary)]"
											aria-label={_t('st.toggle_desktop_notifications')}
											role="switch"
											aria-checked={notificationStore.preferences.desktopEnabled}
										>
											<span class="absolute left-1 top-1 h-6 w-6 rounded-full transition-transform {notificationStore.preferences.desktopEnabled ? 'translate-x-6 bg-[var(--accent)]' : 'bg-[var(--text-secondary)]'}"></span>
										</button>
									{/if}
								</div>
							</div>

							{#if pushStore.supported}
								<div class="flex items-center justify-between">
									<div>
										<div class="font-medium">{_t('st.push_notifications')}</div>
										<div class="text-sm text-[var(--text-secondary)]">{_t('st.get_notified_when_you_re_offline')}</div>
									</div>
									<button
										onclick={async () => {
											if (pushStore.enabled) {
												await pushStore.unsubscribe();
											} else {
												await pushStore.subscribe();
											}
										}}
										disabled={pushStore.loading}
										class="relative h-8 w-14 rounded-full bg-[var(--bg-tertiary)] transition focus-visible:ring-2 focus-visible:ring-[var(--accent)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-secondary)] disabled:opacity-50"
										aria-label={_t('st.toggle_push_notifications')}
										role="switch"
										aria-checked={pushStore.enabled}
									>
										<span class="absolute left-1 top-1 h-6 w-6 rounded-full transition-transform {pushStore.enabled ? 'translate-x-6 bg-[var(--accent)]' : 'bg-[var(--text-secondary)]'}"></span>
									</button>
								</div>
							{/if}

							<div class="flex items-center justify-between">
								<div>
									<div class="font-medium">{_t('st.default_channel_notifications')}</div>
									<div class="text-sm text-[var(--text-secondary)]">{_t('st.notification_level_for_channels')}</div>
								</div>
								<select
									value={notificationStore.preferences.defaultChannelLevel}
									onchange={(e) => {
										notificationStore.preferences = { ...notificationStore.preferences, defaultChannelLevel: e.currentTarget.value as 'all' | 'mentions' | 'nothing' };
										notificationStore.save();
									}}
									class="rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-1.5 text-sm text-[var(--text-primary)] outline-none focus:border-[var(--accent)]"
								>
									<option value="all">{_t('st.all_messages')}</option>
									<option value="mentions">Only @mentions</option>
									<option value="nothing">{_t('st.nothing')}</option>
								</select>
							</div>
						</div>
					</section>

				<!-- ══════════════════ CHAT TAB ══════════════════ -->
				{:else if activeTab === 'chat'}
					<h2 class="mb-6 text-xl font-bold">Chat</h2>

					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.input')}</h3>

						<!-- Send behavior -->
						<div class="mb-4 flex items-center justify-between">
							<div>
								<div class="font-medium">{_t('st.send_messages_with')}</div>
								<div class="text-sm text-[var(--text-secondary)]">{_t('st.choose_how_to_send_a_message')}</div>
							</div>
							<div class="flex overflow-hidden rounded-lg border border-white/10">
								<button
									onclick={() => preferencesStore.set('sendBehavior', 'enter')}
									class="px-3 py-1.5 text-sm transition {preferencesStore.preferences.sendBehavior === 'enter' ? 'bg-[var(--accent)] text-white' : 'text-[var(--text-secondary)] hover:bg-white/5'}"
								>
									Enter
								</button>
								<button
									onclick={() => preferencesStore.set('sendBehavior', 'ctrl-enter')}
									class="border-l border-white/10 px-3 py-1.5 text-sm transition {preferencesStore.preferences.sendBehavior === 'ctrl-enter' ? 'bg-[var(--accent)] text-white' : 'text-[var(--text-secondary)] hover:bg-white/5'}"
								>
									Ctrl+Enter
								</button>
							</div>
						</div>

						<!-- Formatting toolbar -->
						<div class="flex items-center justify-between">
							<div>
								<div class="font-medium">{_t('st.formatting_toolbar')}</div>
								<div class="text-sm text-[var(--text-secondary)]">{_t('st.show_bold_italic_etc_below_input')}</div>
							</div>
							<button
								onclick={() => preferencesStore.set('showFormattingToolbar', !preferencesStore.preferences.showFormattingToolbar)}
								class="relative h-8 w-14 rounded-full bg-[var(--bg-tertiary)] transition focus-visible:ring-2 focus-visible:ring-[var(--accent)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-secondary)]"
								aria-label={_t('st.toggle_formatting_toolbar')}
								role="switch"
								aria-checked={preferencesStore.preferences.showFormattingToolbar}
							>
								<span class="absolute left-1 top-1 h-6 w-6 rounded-full transition-transform {preferencesStore.preferences.showFormattingToolbar ? 'translate-x-6 bg-[var(--accent)]' : 'bg-[var(--text-secondary)]'}"></span>
							</button>
						</div>
					</section>

					<section class="rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.display')}</h3>

						<!-- Link Previews -->
						<div class="flex items-center justify-between">
							<div>
								<div class="font-medium">{_t('st.link_previews')}</div>
								<div class="text-sm text-[var(--text-secondary)]">{_t('st.show_website_previews_for_links')}</div>
							</div>
							<button
								onclick={() => preferencesStore.set('showLinkPreviews', !preferencesStore.preferences.showLinkPreviews)}
								class="relative h-8 w-14 rounded-full bg-[var(--bg-tertiary)] transition focus-visible:ring-2 focus-visible:ring-[var(--accent)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-secondary)]"
								aria-label={_t('st.toggle_link_previews')}
								role="switch"
								aria-checked={preferencesStore.preferences.showLinkPreviews}
							>
								<span class="absolute left-1 top-1 h-6 w-6 rounded-full transition-transform {preferencesStore.preferences.showLinkPreviews ? 'translate-x-6 bg-[var(--accent)]' : 'bg-[var(--text-secondary)]'}"></span>
							</button>
						</div>
						<div class="mt-4 flex items-center justify-between">
							<div>
								<div class="font-medium">{_t('st.send_read_receipts')}</div>
								<div class="text-sm text-[var(--text-secondary)]">{_t('st.let_others_see_when_you_ve_read_thei')}</div>
							</div>
							<button
								onclick={() => preferencesStore.set('sendReadReceipts', !preferencesStore.preferences.sendReadReceipts)}
								class="relative h-8 w-14 rounded-full bg-[var(--bg-tertiary)] transition focus-visible:ring-2 focus-visible:ring-[var(--accent)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-secondary)]"
								aria-label={_t('st.toggle_read_receipts')}
								role="switch"
								aria-checked={preferencesStore.preferences.sendReadReceipts}
							>
								<span class="absolute left-1 top-1 h-6 w-6 rounded-full transition-transform {preferencesStore.preferences.sendReadReceipts ? 'translate-x-6 bg-[var(--accent)]' : 'bg-[var(--text-secondary)]'}"></span>
							</button>
						</div>
					</section>

				<!-- ══════════════════ VOICE TAB ══════════════════ -->
				{:else if activeTab === 'voice'}
					<h2 class="mb-6 text-xl font-bold">{_t('st.voice_audio')}</h2>

					<!-- ── Input Device ── -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.input_device')}</h3>

						<div class="mb-4">
							<label for="mic-select" class="mb-1.5 block text-sm font-medium text-[var(--text-primary)]">{_t('st.microphone')}</label>
							<select id="mic-select"
								value={audioDeviceStore.selectedInputId}
								onchange={(e) => {
									const id = e.currentTarget.value;
									audioDeviceStore.setInputDevice(id);
									if (voiceStore.isInCall) webrtcManager.switchInputDevice(id);
									if (testActive) { stopMicTest(); startMicTest(); }
								}}
								class="w-full rounded-lg border border-white/10 bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] outline-none transition focus:border-[var(--accent)]"
							>
								<option value="">{_t('st.system_default')}</option>
								{#each audioDeviceStore.inputDevices as device}
									<option value={device.deviceId}>{device.label}</option>
								{/each}
							</select>
						</div>

						<div class="mb-4">
							<div class="mb-2 flex items-center justify-between">
								<span class="text-sm font-medium text-[var(--text-primary)]">{_t('st.mic_test')}</span>
								<button
									onclick={() => testActive ? stopMicTest() : startMicTest()}
									class="rounded-lg border border-white/10 px-3 py-1.5 text-xs font-medium text-[var(--text-primary)] transition hover:bg-white/5 {testActive ? 'border-red-500/50 text-red-400' : ''}"
								>
									{testActive ? 'Stop Test' : 'Test Microphone'}
								</button>
							</div>
							<div class="h-2.5 rounded-full bg-white/10 overflow-hidden">
								<div
									class="h-full rounded-full transition-all duration-75 {testLevel > 70 ? 'bg-yellow-400' : 'bg-green-400'}"
									style="width: {testActive ? testLevel : 0}%"
								></div>
							</div>
						</div>

						<div>
							<div class="mb-2 flex items-center justify-between">
								<span class="text-sm font-medium text-[var(--text-primary)]">{_t('st.input_volume')}</span>
								<span class="text-sm text-[var(--text-secondary)]">{preferencesStore.preferences.inputGain}%</span>
							</div>
							<input
								type="range"
								min="0"
								max="200"
								value={preferencesStore.preferences.inputGain}
								oninput={(e) => webrtcManager.setMicGain(parseInt(e.currentTarget.value))}
								class="h-1.5 w-full cursor-pointer appearance-none rounded-full bg-white/10 accent-[var(--accent)]"
							/>
							<div class="mt-1 flex justify-between text-[10px] text-[var(--text-secondary)]">
								<span>0%</span>
								<span>100%</span>
								<span>200%</span>
							</div>
						</div>
					</section>

					<!-- ── Output Device ── -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.output_device')}</h3>

						<div class="mb-4">
							<label for="speaker-select" class="mb-1.5 block text-sm font-medium text-[var(--text-primary)]">{_t('st.speaker')}</label>
							{#if audioDeviceStore.supportsOutputSelection}
								<select id="speaker-select"
									value={audioDeviceStore.selectedOutputId}
									onchange={(e) => audioDeviceStore.setOutputDevice(e.currentTarget.value)}
									class="w-full rounded-lg border border-white/10 bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] outline-none transition focus:border-[var(--accent)]"
								>
									<option value="">{_t('st.system_default')}</option>
									{#each audioDeviceStore.outputDevices as device}
										<option value={device.deviceId}>{device.label}</option>
									{/each}
								</select>
							{:else}
								<p class="text-sm text-[var(--text-secondary)]">
									{_t('st.your_browser_does_not_support_output')}
								</p>
							{/if}
						</div>

						<div>
							<div class="mb-2 flex items-center justify-between">
								<span class="text-sm font-medium text-[var(--text-primary)]">{_t('st.output_volume')}</span>
								<span class="text-sm text-[var(--text-secondary)]">{preferencesStore.preferences.outputVolume}%</span>
							</div>
							<input
								type="range"
								min="0"
								max="200"
								value={preferencesStore.preferences.outputVolume}
								oninput={(e) => preferencesStore.set('outputVolume', parseInt(e.currentTarget.value))}
								class="h-1.5 w-full cursor-pointer appearance-none rounded-full bg-white/10 accent-[var(--accent)]"
							/>
							<div class="mt-1 flex justify-between text-[10px] text-[var(--text-secondary)]">
								<span>0%</span>
								<span>100%</span>
								<span>200%</span>
							</div>
						</div>
					</section>

					<!-- ── Noise Suppression ── -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.noise_suppression')}</h3>
						<p class="mb-4 text-sm text-[var(--text-secondary)]">
							{_t('st.reduce_background_noise_during_voice')}
						</p>

						<div class="grid grid-cols-2 gap-3">
							{#each nsLevels as level}
								<button
									onclick={async () => {
										preferencesStore.set('noiseSuppression', level.id);
										if (voiceStore.isInCall) {
											await webrtcManager.setNoiseSuppressionLevel(level.id);
										}
									}}
									class="rounded-lg border p-4 text-left transition
										{preferencesStore.preferences.noiseSuppression === level.id
											? 'border-[var(--accent)] bg-[var(--accent)]/10'
											: 'border-white/10 hover:border-white/20'}"
								>
									<div class="mb-1 text-sm font-medium">{level.label}</div>
									<div class="text-xs text-[var(--text-secondary)]">{level.desc}</div>
									{#if level.cpu}
										<div class="mt-1.5 inline-block rounded-full bg-white/5 px-2 py-0.5 text-[10px] text-[var(--text-secondary)]">{level.cpu}</div>
									{/if}
								</button>
							{/each}
						</div>

						{#if voiceStore.isInCall}
							<div class="mt-3 flex items-center gap-2 text-xs text-[var(--accent)]">
								<span class="h-1.5 w-1.5 rounded-full bg-[var(--accent)] animate-pulse"></span>
								{_t('st.changes_apply_immediately_to_your_ac')}
							</div>
						{/if}
					</section>

					<!-- ── Advanced ── -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.advanced')}</h3>

						<div class="mb-4 flex items-center justify-between">
							<div>
								<div class="text-sm font-medium text-[var(--text-primary)]">{_t('st.echo_cancellation')}</div>
								<div class="text-xs text-[var(--text-secondary)]">{_t('st.removes_echo_from_speakers_feeding_b')}</div>
							</div>
							<button aria-label={_t('st.toggle_echo_cancellation')}
								role="switch"
								aria-checked={preferencesStore.preferences.echoCancellation}
								onclick={() => preferencesStore.set('echoCancellation', !preferencesStore.preferences.echoCancellation)}
								class="relative h-8 w-14 rounded-full bg-[var(--bg-tertiary)] transition focus-visible:ring-2 focus-visible:ring-[var(--accent)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-secondary)]"
							>
								<span class="absolute left-1 top-1 h-6 w-6 rounded-full transition-transform {preferencesStore.preferences.echoCancellation ? 'translate-x-6 bg-[var(--accent)]' : 'bg-[var(--text-secondary)]'}"></span>
							</button>
						</div>

						<div class="flex items-center justify-between">
							<div>
								<div class="text-sm font-medium text-[var(--text-primary)]">{_t('st.auto_gain_control')}</div>
								<div class="text-xs text-[var(--text-secondary)]">{_t('st.automatically_adjusts_mic_sensitivit')}</div>
							</div>
							<button aria-label={_t('st.toggle_auto_gain_control')}
								role="switch"
								aria-checked={preferencesStore.preferences.autoGainControl}
								onclick={() => preferencesStore.set('autoGainControl', !preferencesStore.preferences.autoGainControl)}
								class="relative h-8 w-14 rounded-full bg-[var(--bg-tertiary)] transition focus-visible:ring-2 focus-visible:ring-[var(--accent)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-secondary)]"
							>
								<span class="absolute left-1 top-1 h-6 w-6 rounded-full transition-transform {preferencesStore.preferences.autoGainControl ? 'translate-x-6 bg-[var(--accent)]' : 'bg-[var(--text-secondary)]'}"></span>
							</button>
						</div>

						<p class="mt-3 text-xs text-[var(--text-secondary)]">
							{_t('st.these_settings_take_effect_on_your_n')}
						</p>
					</section>

					<!-- ── Voice Activation ── -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.voice_activation')}</h3>
						<p class="mb-4 text-sm text-[var(--text-secondary)]">
							{_t('st.choose_how_your_microphone_activates')}
						</p>

						<div class="grid grid-cols-1 gap-3 sm:grid-cols-3">
							{#each voiceActivationModes as mode}
								<button
									onclick={() => preferencesStore.set('voiceActivationMode', mode.id)}
									class="rounded-lg border p-4 text-left transition
										{preferencesStore.preferences.voiceActivationMode === mode.id
											? 'border-[var(--accent)] bg-[var(--accent)]/10'
											: 'border-white/10 hover:border-white/20'}"
								>
									<div class="mb-1 text-sm font-medium">{mode.label}</div>
									<div class="text-xs text-[var(--text-secondary)]">{mode.desc}</div>
								</button>
							{/each}
						</div>

						{#if preferencesStore.preferences.voiceActivationMode === 'push-to-talk'}
							<div class="mt-4 flex items-center justify-between rounded-lg border border-white/10 bg-[var(--bg-primary)] p-4">
								<div>
									<div class="text-sm font-medium text-[var(--text-primary)]">{_t('st.push_to_talk_key')}</div>
									<div class="text-xs text-[var(--text-secondary)]">{_t('st.hold_this_key_to_unmute_while_in_a_c')}</div>
								</div>
								<button
									onclick={() => startRecordingKey('ptt')}
									class="rounded-lg border border-white/10 px-4 py-2 text-sm font-mono text-[var(--text-primary)] transition hover:border-[var(--accent)] hover:bg-white/5"
								>
									{#if recordingKeybind === 'ptt'}
										<span class="text-[var(--accent)] animate-pulse">{_t('st.press_a_key')}</span>
									{:else}
										{formatKeyForDisplay(preferencesStore.preferences.pttKey)}
									{/if}
								</button>
							</div>
						{:else if preferencesStore.preferences.voiceActivationMode === 'toggle-mute'}
							<div class="mt-4 flex items-center justify-between rounded-lg border border-white/10 bg-[var(--bg-primary)] p-4">
								<div>
									<div class="text-sm font-medium text-[var(--text-primary)]">{_t('st.toggle_mute_key')}</div>
									<div class="text-xs text-[var(--text-secondary)]">{_t('st.press_this_key_to_toggle_your_microp')}</div>
								</div>
								<button
									onclick={() => startRecordingKey('toggle')}
									class="rounded-lg border border-white/10 px-4 py-2 text-sm font-mono text-[var(--text-primary)] transition hover:border-[var(--accent)] hover:bg-white/5"
								>
									{#if recordingKeybind === 'toggle'}
										<span class="text-[var(--accent)] animate-pulse">{_t('st.press_a_key')}</span>
									{:else}
										{formatKeyForDisplay(preferencesStore.preferences.toggleMuteKey)}
									{/if}
								</button>
							</div>
						{/if}
					</section>

					<!-- ── Stream Focus ── -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.stream_focus')}</h3>

						<div class="flex items-center justify-between">
							<div>
								<div class="text-sm font-medium text-[var(--text-primary)]">{_t('st.auto_focus_streams')}</div>
								<div class="text-xs text-[var(--text-secondary)]">{_t('st.hide_participant_tiles_when_someone_')}</div>
							</div>
							<button aria-label={_t('st.toggle_auto_focus_streams')}
								role="switch"
								aria-checked={preferencesStore.preferences.autoHideParticipantsOnStream}
								onclick={() => preferencesStore.set('autoHideParticipantsOnStream', !preferencesStore.preferences.autoHideParticipantsOnStream)}
								class="relative h-8 w-14 rounded-full bg-[var(--bg-tertiary)] transition focus-visible:ring-2 focus-visible:ring-[var(--accent)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-secondary)]"
							>
								<span class="absolute left-1 top-1 h-6 w-6 rounded-full transition-transform {preferencesStore.preferences.autoHideParticipantsOnStream ? 'translate-x-6 bg-[var(--accent)]' : 'bg-[var(--text-secondary)]'}"></span>
							</button>
						</div>

						<p class="mt-3 text-xs text-[var(--text-secondary)]">
							{_t('st.you_can_always_toggle_between_focuse')}
						</p>
					</section>

					<!-- ── Call Background ── -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.call_background')}</h3>
						<p class="mb-4 text-sm text-[var(--text-secondary)]">
							{_t('st.set_a_background_for_your_video_tile')}
						</p>

						<!-- Live preview -->
						<div
							class="mb-4 flex items-center justify-center overflow-hidden rounded-lg"
							style="aspect-ratio: 16/9; max-width: 240px; {voiceBackgroundStyle(preferencesStore.preferences.voiceBackground) || 'background: var(--bg-tertiary);'}"
						>
							<div class="flex h-12 w-12 items-center justify-center rounded-full bg-black/30">
								<svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-white/60" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
							</div>
						</div>

						<!-- Type selector -->
						<div class="mb-4 flex flex-wrap gap-2">
							{#each [
								{ id: 'none', label: 'None' },
								{ id: 'solid', label: 'Solid' },
								{ id: 'gradient', label: 'Gradient' },
								{ id: 'preset', label: 'Preset' },
								{ id: 'custom', label: 'Image' }
							] as opt}
								<button
									onclick={() => { voiceBgType = opt.id as VoiceBackgroundType; applyVoiceBg(); }}
									class="rounded-lg px-3 py-1.5 text-xs font-medium transition {voiceBgType === opt.id ? 'bg-[var(--accent)] text-white' : 'bg-white/5 text-[var(--text-secondary)] hover:bg-white/10'}"
								>
									{opt.label}
								</button>
							{/each}
						</div>

						<!-- Per-type controls -->
						{#if voiceBgType === 'solid'}
							<div class="flex items-center gap-3">
								<input type="color" bind:value={voiceBgColor} onchange={applyVoiceBg} class="h-8 w-8 cursor-pointer rounded border-0 bg-transparent" aria-label={_t('st.background_color')} />
								<span class="text-sm text-[var(--text-secondary)]">{voiceBgColor}</span>
							</div>
						{:else if voiceBgType === 'gradient'}
							<div class="flex flex-col gap-3">
								<div class="flex items-center gap-3">
									<span class="text-xs text-[var(--text-secondary)]">{_t('st.from')}</span>
									<input type="color" bind:value={voiceBgGradFrom} onchange={applyVoiceBg} class="h-7 w-7 cursor-pointer rounded border-0 bg-transparent" aria-label={_t('st.gradient_start_color')} />
									<span class="text-xs text-[var(--text-secondary)]">To</span>
									<input type="color" bind:value={voiceBgGradTo} onchange={applyVoiceBg} class="h-7 w-7 cursor-pointer rounded border-0 bg-transparent" aria-label={_t('st.gradient_end_color')} />
								</div>
								<div class="flex items-center gap-2">
									<span class="text-xs text-[var(--text-secondary)]">{_t('st.angle')}</span>
									<input type="range" min="0" max="360" bind:value={voiceBgGradAngle} oninput={applyVoiceBg}
										class="h-1.5 w-32 cursor-pointer appearance-none rounded-full bg-white/10 accent-[var(--accent)]" aria-label={_t('st.gradient_angle')} />
									<span class="text-xs text-[var(--text-secondary)]">{voiceBgGradAngle}°</span>
								</div>
							</div>
						{:else if voiceBgType === 'preset'}
							<div class="grid grid-cols-2 sm:grid-cols-3 gap-2">
								{#each Object.entries(VOICE_BG_PRESETS) as [id, preset]}
									<button
										onclick={() => { voiceBgPresetId = id; applyVoiceBg(); }}
										class="overflow-hidden rounded-lg border-2 transition {voiceBgPresetId === id ? 'border-[var(--accent)]' : 'border-transparent hover:border-white/20'}"
									>
										<div class="flex items-center justify-center rounded" style="aspect-ratio: 16/9; background: {preset.css};">
											<span class="rounded bg-black/40 px-2 py-0.5 text-[10px] font-medium text-white">{preset.label}</span>
										</div>
									</button>
								{/each}
							</div>
						{:else if voiceBgType === 'custom'}
							<div class="flex items-center gap-3">
								<input bind:this={voiceBgInputEl} type="file" accept="image/*" class="hidden" onchange={handleVoiceBgUpload} />
								<button
									onclick={() => voiceBgInputEl?.click()}
									disabled={voiceBgUploading}
									class="rounded-lg bg-white/5 px-3 py-1.5 text-sm text-[var(--text-secondary)] transition hover:bg-white/10"
								>
									{voiceBgUploading ? 'Uploading...' : voiceBgCustomUrl ? 'Change Image' : 'Upload Image'}
								</button>
								{#if voiceBgCustomUrl}
									<span class="text-xs text-[var(--text-secondary)]">{_t('st.image_set')}</span>
								{/if}
							</div>
							<p class="mt-2 text-xs text-[var(--text-secondary)]">{_t('st.max_10_mb_png_jpeg_webp_or_gif')}</p>
						{/if}
					</section>

				{:else if activeTab === 'security'}
					<h2 class="mb-6 text-xl font-bold">{_t('st.security')}</h2>

					<!-- 2FA -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.two_factor_authentication')}</h3>
						<p class="mb-4 text-sm text-[var(--text-secondary)]">
							{_t('st.add_an_extra_layer_of_security_with_')}
						</p>

						{#if totpMessage}
							<div class="mb-4 rounded-lg border border-green-500/20 bg-green-500/10 px-4 py-3 text-sm text-green-400">
								{totpMessage}
							</div>
						{/if}
						{#if totpError}
							<div class="mb-4 rounded-lg border border-red-500/20 bg-red-500/10 px-4 py-3 text-sm text-red-400">
								{totpError}
							</div>
						{/if}

						{#if showTotpSetup && totpSetup}
							<div class="rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] p-4">
								<p class="mb-3 text-sm">{_t('st.scan_with_your_authenticator_app_or_')}</p>
								<div class="mb-3 flex justify-center rounded-lg bg-white p-4">
									{#if totpQrDataUrl}
										<img src={totpQrDataUrl} alt={_t('st.totp_qr_code')} class="h-[200px] w-[200px]" />
									{:else}
										<p class="text-xs text-gray-500">{_t('st.generating_qr_code')}</p>
									{/if}
								</div>
								<div class="mb-4">
									<span class="mb-1 block text-xs text-[var(--text-secondary)]">{_t('st.manual_entry_secret')}</span>
									<code class="block select-all rounded bg-[var(--bg-tertiary)] px-3 py-2 font-mono text-sm">
										{totpSetup.secret}
									</code>
								</div>
								<form onsubmit={handleVerifyTotp} class="flex gap-2">
									<input
										type="text"
										bind:value={totpCode}
										placeholder={_t('st.enter_6_digit_code')}
										maxlength="6"
										pattern="[0-9]{6}"
										class="flex-1 rounded-xl border border-[var(--border)] bg-[var(--bg-secondary)] px-3 py-2 text-sm text-[var(--text-primary)] outline-none focus:border-[var(--accent)]"
									/>
									<button
										type="submit"
										class="rounded-lg bg-[var(--accent)] px-4 py-2 text-sm font-medium text-white transition hover:bg-[var(--accent-hover)]"
									>
										{_t('st.verify_enable')}
									</button>
								</form>
							</div>
						{:else if showTotpDisable}
							<form onsubmit={handleDisableTotp} class="flex gap-2">
								<input
									type="text"
									bind:value={disableCode}
									placeholder={_t('st.enter_2fa_code_to_disable')}
									maxlength="6"
									pattern="[0-9]{6}"
									class="flex-1 rounded-xl border border-[var(--border)] bg-[var(--bg-secondary)] px-3 py-2 text-sm text-[var(--text-primary)] outline-none focus:border-[var(--accent)]"
								/>
								<button
									type="submit"
									class="rounded-lg bg-[var(--danger)] px-4 py-2 text-sm font-medium text-white transition hover:opacity-90"
								>
									{_t('st.disable_2fa')}
								</button>
								<button
									type="button"
									onclick={() => { showTotpDisable = false; disableCode = ''; }}
									class="rounded-lg border border-white/10 px-4 py-2 text-sm text-[var(--text-secondary)] transition hover:bg-white/5"
								>
									{_t('st.cancel')}
								</button>
							</form>
						{:else}
							<div class="flex gap-3">
								<button
									onclick={handleSetupTotp}
									class="rounded-lg bg-[var(--accent)] px-4 py-2 text-sm font-medium text-white transition hover:bg-[var(--accent-hover)]"
								>
									{_t('st.enable_2fa')}
								</button>
								<button
									onclick={() => (showTotpDisable = true)}
									class="rounded-lg border border-white/10 px-4 py-2 text-sm text-[var(--text-secondary)] transition hover:bg-white/5 hover:text-[var(--text-primary)]"
								>
									{_t('st.disable_2fa')}
								</button>
							</div>
						{/if}
					</section>

					<!-- Recovery Code -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.recovery_code')}</h3>
						<p class="mb-4 text-sm text-[var(--text-secondary)]">
							{_t('st.your_recovery_code_lets_you_reset_yo')}
						</p>

						{#if recoveryError}
							<div class="mb-4 rounded-lg border border-red-500/20 bg-red-500/10 px-4 py-3 text-sm text-red-400">
								{recoveryError}
							</div>
						{/if}

						{#if showRecoveryCode}
							<div class="mb-4 rounded-lg bg-[var(--bg-primary)] p-4">
								<p class="mb-2 text-sm font-medium text-[var(--text-primary)]">{_t('st.your_new_recovery_code')}</p>
								<p class="mb-3 text-xs text-[var(--text-secondary)]">
									{_t('st.save_this_code_somewhere_safe_it_rep')}
								</p>
								<div class="mb-3 rounded-lg bg-[var(--bg-tertiary,var(--bg-primary))] p-3 text-center">
									<code class="select-all font-mono text-lg font-bold tracking-wider text-[var(--accent)]">
										{recoveryCode}
									</code>
								</div>
								<div class="flex gap-2">
									<button
										onclick={() => {
											navigator.clipboard.writeText(recoveryCode).then(() => {
												copiedRecovery = true;
												setTimeout(() => (copiedRecovery = false), 2000);
											}).catch(() => toastStore.error('Failed to copy to clipboard'));
										}}
										class="flex-1 rounded-lg border border-white/10 px-4 py-2 text-sm text-[var(--text-primary)] transition hover:bg-white/5"
									>
										{copiedRecovery ? 'Copied!' : 'Copy Code'}
									</button>
									<button
										onclick={() => { showRecoveryCode = false; recoveryCode = ''; }}
										class="rounded-lg border border-white/10 px-4 py-2 text-sm text-[var(--text-secondary)] transition hover:bg-white/5"
									>
										{_t('st.done')}
									</button>
								</div>
							</div>
						{:else}
							<button
								onclick={handleRegenerateRecoveryCode}
								disabled={recoveryLoading}
								class="rounded-lg bg-[var(--accent)] px-4 py-2 text-sm font-medium text-white transition hover:bg-[var(--accent-hover)] disabled:opacity-50"
							>
								{recoveryLoading ? 'Generating...' : 'Generate New Recovery Code'}
							</button>
						{/if}
					</section>

					<!-- 2FA Backup Codes -->
					{#if showBackupCodes && backupCodes.length > 0}
						<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
							<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.2fa_backup_codes')}</h3>
							<p class="mb-4 text-sm text-[var(--text-secondary)]">
								{_t('st.each_code_can_only_be_used_once_save')}
							</p>
							<div class="mb-4 grid grid-cols-2 gap-2">
								{#each backupCodes as code}
									<code class="select-all rounded bg-[var(--bg-primary)] px-3 py-2 text-center font-mono text-sm tracking-wider text-[var(--text-primary)]">
										{code}
									</code>
								{/each}
							</div>
							<div class="flex gap-2">
								<button
									onclick={() => {
										navigator.clipboard.writeText(backupCodes.join('\n')).then(
										() => toastStore.success('Backup codes copied'),
										() => toastStore.error('Failed to copy to clipboard'),
									);
									}}
									class="rounded-lg border border-white/10 px-4 py-2 text-sm text-[var(--text-primary)] transition hover:bg-white/5"
								>
									{_t('st.copy_all')}
								</button>
								<button
									onclick={() => { showBackupCodes = false; backupCodes = []; }}
									class="rounded-lg border border-white/10 px-4 py-2 text-sm text-[var(--text-secondary)] transition hover:bg-white/5"
								>
									{_t('st.done')}
								</button>
							</div>
						</section>
					{:else}
						<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
							<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.2fa_backup_codes')}</h3>
							<p class="mb-4 text-sm text-[var(--text-secondary)]">
								{_t('st.regenerate_backup_codes_if_you_ve_us')}
							</p>
							{#if backupCodeError}
								<div class="mb-4 rounded-lg border border-red-500/20 bg-red-500/10 px-4 py-3 text-sm text-red-400">
									{backupCodeError}
								</div>
							{/if}
							<form onsubmit={handleRegenerateBackupCodes} class="flex gap-2">
								<input
									type="text"
									bind:value={backupCodeTotpInput}
									placeholder={_t('st.enter_2fa_code')}
									maxlength="6"
									pattern="[0-9]{6}"
									class="flex-1 rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] outline-none focus:border-[var(--accent)]"
								/>
								<button
									type="submit"
									class="rounded-lg bg-[var(--accent)] px-4 py-2 text-sm font-medium text-white transition hover:bg-[var(--accent-hover)]"
								>
									{_t('st.regenerate')}
								</button>
							</form>
						</section>
					{/if}

					<!-- Sessions -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<div class="mb-4 flex items-center justify-between">
							<h3 class="text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.active_sessions')}</h3>
							<button
								onclick={handleLogoutAll}
								disabled={revokingAll}
								class="rounded-lg border border-red-500/20 px-3 py-1.5 text-xs font-medium text-red-400 transition hover:bg-red-500/10 disabled:opacity-50"
							>
								{revokingAll ? 'Revoking...' : 'Revoke All'}
							</button>
						</div>

						{#if sessionsLoading}
							<p class="text-sm text-[var(--text-secondary)]">{_t('st.loading_sessions')}</p>
						{:else if sessionsError}
							<p class="text-sm text-red-400">{sessionsError}</p>
						{:else if sessions.length === 0}
							<p class="text-sm text-[var(--text-secondary)]">{_t('st.no_active_sessions')}</p>
						{:else}
							<div class="space-y-3">
								{#each sessions as session}
									{@const isExpired = new Date(session.expires_at) < new Date()}
									{@const expiresIn = Math.max(0, Math.round((new Date(session.expires_at).getTime() - Date.now()) / (1000 * 60 * 60 * 24)))}
									<div class="flex items-center justify-between rounded-lg border border-white/5 bg-[var(--bg-primary)] px-4 py-3 {isExpired ? 'opacity-50' : ''}">
										<div class="flex items-center gap-3">
											<div class="flex h-8 w-8 items-center justify-center rounded-lg bg-white/5 text-[var(--text-secondary)]">
												{#if session.device_name?.includes('Desktop')}
													<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3" width="20" height="14" rx="2" /><line x1="8" y1="21" x2="16" y2="21" /><line x1="12" y1="17" x2="12" y2="21" /></svg>
												{:else if session.device_name?.includes('Android') || session.device_name?.includes('iOS')}
													<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="2" width="14" height="20" rx="2" /><line x1="12" y1="18" x2="12.01" y2="18" /></svg>
												{:else}
													<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10" /><line x1="2" y1="12" x2="22" y2="12" /><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" /></svg>
												{/if}
											</div>
											<div>
												<div class="text-sm font-medium">{session.device_name ?? 'Unknown device'}</div>
												<div class="text-xs text-[var(--text-secondary)]">
													{session.ip_address ?? 'Unknown IP'} &middot; {new Date(session.created_at).toLocaleDateString()}
													{#if isExpired}
														&middot; <span class="text-red-400">{_t('st.expired')}</span>
													{:else}
														&middot; Expires in {expiresIn}d
													{/if}
												</div>
											</div>
										</div>
										<button
											onclick={() => handleRevokeSession(session.id)}
											disabled={revokingSessionId === session.id}
											class="rounded px-2 py-1 text-xs text-red-400 transition hover:bg-red-500/10 disabled:opacity-50"
										>
											{revokingSessionId === session.id ? 'Revoking...' : 'Revoke'}
										</button>
									</div>
								{/each}
							</div>
						{/if}
					</section>

					<!-- Encryption info -->
					<section class="rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.encryption')}</h3>
						<div class="space-y-4 text-sm text-[var(--text-secondary)]">
							<div class="flex items-center justify-between">
								<span>{_t('st.end_to_end_encryption')}</span>
								<span class="rounded-full bg-green-500/10 px-3 py-1 text-xs text-green-400">{_t('st.active')}</span>
							</div>
							<div class="flex items-center justify-between">
								<span>{_t('st.protocol')}</span>
								<span class="text-xs text-[var(--text-primary)]">{_t('st.x3dh_double_ratchet_dms_sender_keys_')}</span>
							</div>

							{#if !fingerprintLoaded}
								<button
									onclick={loadOwnFingerprint}
									disabled={fingerprintLoading}
									class="text-xs text-[var(--accent)] hover:underline disabled:opacity-50"
								>
									{fingerprintLoading ? 'Loading...' : 'Show identity fingerprint'}
								</button>
								{#if fingerprintError}
									<p class="text-xs text-red-400">{fingerprintError}</p>
								{/if}
							{:else if ownFingerprintSettings}
								<div>
									<div class="mb-1 text-xs font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.your_fingerprint')}</div>
									<div class="relative rounded-lg bg-[var(--bg-primary)] p-3">
										<div class="select-all break-all pr-8 font-mono text-xs leading-relaxed tracking-widest text-[var(--text-primary)]">
											{ownFingerprintSettings}
										</div>
										<button
											onclick={() => {
												navigator.clipboard.writeText(ownFingerprintSettings);
												fingerprintCopied = true;
												setTimeout(() => fingerprintCopied = false, 2000);
											}}
											class="absolute right-2 top-2 rounded p-1 text-[var(--text-secondary)] transition hover:bg-white/10 hover:text-[var(--text-primary)]"
											title={_t('st.copy_fingerprint')}
											aria-label={_t('st.copy_fingerprint')}
										>
											{#if fingerprintCopied}
												<svg class="h-4 w-4 text-green-400" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
											{:else}
												<svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
											{/if}
										</button>
									</div>
								</div>
								{#if ownPublicKeyHex}
									<div>
										<div class="mb-1 text-xs font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.public_key')}</div>
										<code class="block select-all break-all rounded-lg bg-[var(--bg-primary)] px-3 py-2 font-mono text-[10px] leading-relaxed text-[var(--text-secondary)]">
											{ownPublicKeyHex}
										</code>
									</div>
								{/if}
							{/if}

							<p class="text-xs text-[var(--text-tertiary)]">
								{_t('st.keys_are_stored_only_on_this_device_')}
							</p>
						</div>
					</section>

				<!-- ══════════════════ ACCOUNT TAB ══════════════════ -->
				{:else if activeTab === 'account'}
					<h2 class="mb-6 text-xl font-bold">{_t('st.account')}</h2>

					<!-- Change Password -->
					<section class="mb-6 rounded-2xl bg-[var(--bg-secondary)] p-6 shadow-sm">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-[var(--text-secondary)]">{_t('st.change_password')}</h3>
						<p class="mb-4 text-sm text-[var(--text-secondary)]">
							{_t('st.changing_your_password_will_sign_you')}
						</p>

						{#if passwordError}
							<div class="mb-4 rounded-lg border border-red-500/20 bg-red-500/10 px-4 py-3 text-sm text-red-400">
								{passwordError}
							</div>
						{/if}

						<form onsubmit={handleChangePassword} class="space-y-4">
							<div>
								<label for="currentPw" class="mb-1 block text-sm font-medium">{_t('st.current_password')}</label>
								<input
									id="currentPw"
									type="password"
									bind:value={currentPassword}
									required
									autocomplete="current-password"
									class="w-full rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] outline-none focus:border-[var(--accent)]"
								/>
							</div>
							<div>
								<label for="newPw" class="mb-1 block text-sm font-medium">{_t('st.new_password')}</label>
								<input
									id="newPw"
									type="password"
									bind:value={newPassword}
									required
									autocomplete="new-password"
									class="w-full rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] outline-none focus:border-[var(--accent)]"
								/>
								{#if newPassword.length > 0}
									<div class="mt-2 space-y-1 text-xs">
										<div class={pwHasLength ? 'text-green-400' : 'text-[var(--text-secondary)]'}>{pwHasLength ? '\u2713' : '\u2717'} {_t('st.at_least_8_characters')}</div>
										<div class={pwHasUpper ? 'text-green-400' : 'text-[var(--text-secondary)]'}>{pwHasUpper ? '\u2713' : '\u2717'} {_t('st.one_uppercase_letter')}</div>
										<div class={pwHasLower ? 'text-green-400' : 'text-[var(--text-secondary)]'}>{pwHasLower ? '\u2713' : '\u2717'} {_t('st.one_lowercase_letter')}</div>
										<div class={pwHasDigit ? 'text-green-400' : 'text-[var(--text-secondary)]'}>{pwHasDigit ? '\u2713' : '\u2717'} {_t('st.one_digit')}</div>
										<div class={pwHasSpecial ? 'text-green-400' : 'text-[var(--text-secondary)]'}>{pwHasSpecial ? '\u2713' : '\u2717'} {_t('st.one_special_character')}</div>
									</div>
								{/if}
							</div>
							<div>
								<label for="confirmPw" class="mb-1 block text-sm font-medium">{_t('st.confirm_new_password')}</label>
								<input
									id="confirmPw"
									type="password"
									bind:value={confirmPassword}
									required
									autocomplete="new-password"
									class="w-full rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] outline-none focus:border-[var(--accent)]"
								/>
							</div>
							<button
								type="submit"
								disabled={passwordSaving || !pwAllMet || !currentPassword}
								class="rounded-lg bg-[var(--accent)] px-4 py-2 text-sm font-medium text-white transition hover:bg-[var(--accent-hover)] disabled:opacity-50"
							>
								{passwordSaving ? 'Changing...' : 'Change Password'}
							</button>
						</form>
					</section>

					<!-- Danger Zone -->
					<section class="rounded-xl border border-red-500/20 bg-[var(--bg-secondary)] p-6">
						<h3 class="mb-4 text-sm font-semibold uppercase tracking-wider text-red-400">{_t('st.danger_zone')}</h3>
						<div class="flex flex-wrap gap-3">
							<button
								onclick={() => { authStore.logout(); goto('/login'); }}
								class="rounded-lg border border-red-500/20 bg-red-500/10 px-4 py-2 text-sm font-medium text-red-400 transition hover:bg-red-500/20"
							>
								{_t('st.sign_out')}
							</button>
							<button
								onclick={() => { showDeleteConfirm = true; }}
								class="rounded-lg border border-red-500/20 bg-red-500/10 px-4 py-2 text-sm font-medium text-red-400 transition hover:bg-red-500/20"
							>
								{_t('st.delete_account')}
							</button>
							{#if isDesktop && serverUrl}
								<button
									onclick={() => { clearServerUrl(); authStore.logout(); goto('/connect'); }}
									class="rounded-lg border border-red-500/20 bg-red-500/10 px-4 py-2 text-sm font-medium text-red-400 transition hover:bg-red-500/20"
								>
									{_t('st.disconnect_from_server')}
								</button>
							{/if}
						</div>

						{#if showDeleteConfirm}
							<div class="mt-4 rounded-lg border border-red-500/20 bg-[var(--bg-primary)] p-4">
								<p class="mb-3 text-sm text-red-400">
									{_t('st.this_action_is_permanent_and_cannot_')}
								</p>
								{#if deleteError}
									<div class="mb-3 rounded-lg border border-red-500/20 bg-red-500/10 px-4 py-3 text-sm text-red-400">
										{deleteError}
									</div>
								{/if}
								<form onsubmit={handleDeleteAccount} class="flex gap-2">
									<input
										type="password"
										bind:value={deletePassword}
										placeholder={_t('st.confirm_your_password')}
										required
										autocomplete="current-password"
										class="flex-1 rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] px-3 py-2 text-sm text-[var(--text-primary)] outline-none focus:border-red-500/50"
									/>
									<button
										type="submit"
										disabled={deleting}
										class="rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-red-700 disabled:opacity-50"
									>
										{deleting ? 'Deleting...' : 'Delete Forever'}
									</button>
									<button
										type="button"
										onclick={() => { showDeleteConfirm = false; deletePassword = ''; deleteError = ''; }}
										class="rounded-lg border border-white/10 px-4 py-2 text-sm text-[var(--text-secondary)] transition hover:bg-white/5"
									>
										{_t('st.cancel')}
									</button>
								</form>
							</div>
						{/if}
					</section>
				{/if}
			</div>
		</div>
	</div>

	{#if settingsConfirmDialog}
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4" role="dialog" tabindex="-1" aria-modal="true" aria-label={settingsConfirmDialog.title} transition:fade={{ duration: 150 }} onclick={() => settingsConfirmDialog = null} onkeydown={(e) => { if (e.key === 'Escape') settingsConfirmDialog = null; }}>
			<!-- svelte-ignore a11y_no_static_element_interactions -->
			<div class="w-full max-w-sm rounded-2xl bg-[var(--bg-secondary)] p-5 shadow-xl" transition:scale={{ start: 0.95, duration: 200 }} onclick={(e) => e.stopPropagation()} onkeydown={(e) => e.stopPropagation()}>
				<h3 class="mb-2 text-base font-bold text-[var(--text-primary)]">{settingsConfirmDialog.title}</h3>
				<p class="mb-4 text-sm text-[var(--text-secondary)]">{settingsConfirmDialog.message}</p>
				<div class="flex justify-end gap-2">
					<button onclick={() => settingsConfirmDialog = null} class="rounded-lg px-4 py-2 text-sm font-medium text-[var(--text-secondary)] transition hover:bg-white/5 hover:text-[var(--text-primary)]">Cancel</button>
					<button onclick={() => { settingsConfirmDialog?.onConfirm(); settingsConfirmDialog = null; }} class="rounded-lg px-4 py-2 text-sm font-medium text-white transition {settingsConfirmDialog.danger ? 'bg-[var(--danger)] hover:bg-red-600' : 'bg-[var(--accent)] hover:bg-[var(--accent-hover)]'}">{settingsConfirmDialog.confirmLabel}</button>
				</div>
			</div>
		</div>
	{/if}

	<!-- Image Cropper Modal -->
	{#if cropFile && cropTarget}
		<ImageCropper
			imageFile={cropFile}
			aspectRatio={cropTarget === 'avatar' ? 1 : cropTarget === 'banner' ? 3 : undefined}
			circular={cropTarget === 'avatar'}
			onConfirm={uploadCroppedImage}
			onCancel={cancelCrop}
		/>
	{/if}
{/if}
