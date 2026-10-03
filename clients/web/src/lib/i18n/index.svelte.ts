import { en, type MessageKey } from './en';
import { ru } from './ru';

export type Locale = 'ru' | 'en';
export const LOCALES: { code: Locale; label: string }[] = [
	{ code: 'ru', label: 'Русский' },
	{ code: 'en', label: 'English' }
];

const STORAGE_KEY = 'ts-locale';
const dictionaries: Record<Locale, Record<string, string>> = { en, ru };

function detectLocale(): Locale {
	try {
		const saved = localStorage.getItem(STORAGE_KEY);
		if (saved === 'ru' || saved === 'en') return saved;
	} catch {
		// storage unavailable — fall through to device language
	}
	const langs = typeof navigator !== 'undefined' ? (navigator.languages ?? [navigator.language]) : [];
	for (const l of langs) {
		const code = (l ?? '').toLowerCase();
		if (code.startsWith('ru')) return 'ru';
		if (code.startsWith('en')) return 'en';
	}
	return 'ru';
}

class I18n {
	locale = $state<Locale>('ru');
	private initialized = false;

	init() {
		if (this.initialized || typeof window === 'undefined') return;
		this.initialized = true;
		this.locale = detectLocale();
		this.applyDocumentLang();
	}

	set(locale: Locale) {
		this.locale = locale;
		try {
			localStorage.setItem(STORAGE_KEY, locale);
		} catch {
			// ignore
		}
		this.applyDocumentLang();
	}

	private applyDocumentLang() {
		if (typeof document !== 'undefined') document.documentElement.lang = this.locale;
	}

	/** Translate a key, substituting {name} placeholders. Falls back to English, then the key. */
	t(key: MessageKey, params?: Record<string, string | number>): string {
		let text = dictionaries[this.locale][key] ?? en[key] ?? key;
		if (params) {
			for (const [k, v] of Object.entries(params)) {
				text = text.replaceAll(`{${k}}`, String(v));
			}
		}
		return text;
	}
}

export const i18n = new I18n();
export const t = (key: MessageKey, params?: Record<string, string | number>) => i18n.t(key, params);

/**
 * Plural-aware translation. Looks up `<key>.<category>` (one/few/many/other) for the
 * current language, falling back to `<key>.other`. `n` is exposed as {n}.
 */
export function tn(key: string, n: number, params?: Record<string, string | number>): string {
	const category = new Intl.PluralRules(i18n.locale).select(n);
	const dict = dictionaries[i18n.locale];
	const text = dict[`${key}.${category}`] ?? dict[`${key}.other`] ?? en[`${key}.other` as MessageKey] ?? key;
	let out = text;
	for (const [k, v] of Object.entries({ n, ...params })) out = out.replaceAll(`{${k}}`, String(v));
	return out;
}

/**
 * Map technical server error messages to short, friendly, translated text.
 * Unknown messages are returned unchanged so no information is lost.
 */
export function friendlyError(raw: unknown, fallback: MessageKey = 'error.generic'): string {
	const msg = raw instanceof Error ? raw.message : typeof raw === 'string' ? raw : '';
	if (!msg) return t(fallback);
	const m = msg.toLowerCase();
	const rules: [RegExp, MessageKey][] = [
		[/failed to fetch|networkerror|load failed|network request failed/, 'error.network'],
		[/too many failed attempts|temporarily locked|rate.?limit|too many requests/, 'error.tooManyAttempts'],
		[/unauthorized|invalid credentials|invalid username or password/, 'error.badCredentials'],
		[/account is suspended/, 'error.suspended'],
		[/password login is disabled/, 'error.passwordLoginDisabled'],
		[/invite|access code/, 'error.invalidInvite'],
		[/username.*(taken|exists)|already exists/, 'error.usernameTaken'],
		[/password must contain at least one uppercase/, 'error.pw.upper'],
		[/password must contain at least one lowercase/, 'error.pw.lower'],
		[/password must contain at least one digit/, 'error.pw.digit'],
		[/password must contain at least one special/, 'error.pw.special'],
		[/password must be at least/, 'error.pw.short'],
		[/password must be at most/, 'error.pw.long'],
		[/registration.*closed/, 'error.registrationClosed']
	];
	for (const [re, key] of rules) if (re.test(m)) return t(key);
	return msg;
}
