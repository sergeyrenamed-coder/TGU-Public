import { useEffect, useMemo, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { getVersion } from "@tauri-apps/api/app";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { listen } from "@tauri-apps/api/event";
import {
  Activity, Bookmark, CheckCircle2, ChevronRight, CircleHelp,
  ExternalLink, Globe2, Heart, History, LayoutDashboard, LoaderCircle,
  Network, Plus, Power, RefreshCw, Save, Search, Server, Settings,
  ShieldCheck, SlidersHorizontal, Star, Trash2, Wifi, X, XCircle,
  PanelLeftClose, PanelLeftOpen, UserRound, LogOut, Minus, Square
} from "lucide-react";

type Section = "overview" | "catalog" | "selector" | "profiles" | "diagnostics" | "favorites" | "history" | "settings" | "account" | "about";
type RnmdDevice = { id: string; device_name: string; platform: string; last_seen_at: string };
type RnmdOrder = { id: string; product_code: string; status: string; currency: string; amount_minor: number; created_at: string };
type RnmdAccount = { user_id: string; display_name: string; telegram_username: string | null; avatar_url: string | null; devices: RnmdDevice[]; orders: RnmdOrder[]; has_tgu_plus: boolean };
type EngineStatus = {
  engine_ready: boolean;
  upstream_healthy: boolean;
  telegram_connected: boolean;
  endpoint: string;
  selected_upstream: string | null;
  current_route: string;
  active_connections: number;
  last_error: string | null;
  connections_total: number;
  websocket_connections: number;
  cloudflare_connections: number;
  tcp_fallback_connections: number;
  failed_connections: number;
  bytes_up: number;
  bytes_down: number;
  uptime_seconds: number;
  last_check_epoch_ms: number | null;
  last_check_latency_ms: number | null;
  failure_streak: number;
  switch_count: number;
  standby_count: number;
  standby_scanning: boolean;
};
type ProxySummary = {
  id: string; protocol: string; server: string; port: number; country: string | null;
  source_rtt_ms: number | null; uptime: number | null; checks: number | null;
  source_tier: string | null; last_check: string | null; source_verified: boolean;
  supported: boolean; unsupported_reason: string | null;
  community_reports: number; community_unique_reporters: number;
  community_success_rate: number | null; community_median_rtt_ms: number | null;
  ru_verified: boolean; ru_rtt_ms: number | null;
};
type CatalogResponse = {
  count: number; generated_at: string; source: string; from_cache: boolean;
  warning: string | null; proxies: ProxySummary[];
};
type ProxyTestResult = { success: boolean; stage: string; detail: string; elapsed_ms: number };
type CommunityFeedbackStatus = {
  enabled: boolean;
  attempted: number;
  accepted: number;
  rejected: number;
  http_status: number | null;
  message: string;
};
type AutoSelectionResult = {
  selected: ProxySummary | null;
  total_candidates: number;
  tested_candidates: number;
  first_pass_available: number;
  finalists_verified: number;
  verified_latency_ms: number | null;
  message: string;
  community_feedback: CommunityFeedbackStatus | null;
};
type AutoSelectionProgress = {
  is_running: boolean;
  phase: string;
  total: number;
  completed: number;
  available: number;
  finalists_verified: number;
  current_endpoint: string | null;
  best_endpoint: string | null;
  best_latency_ms: number | null;
  elapsed_ms: number;
  message: string | null;
};
const initialAutoProgress: AutoSelectionProgress = {
  is_running: false,
  phase: "Автоподбор ещё не запускался",
  total: 0,
  completed: 0,
  available: 0,
  finalists_verified: 0,
  current_endpoint: null,
  best_endpoint: null,
  best_latency_ms: null,
  elapsed_ms: 0,
  message: null
};
type TypeFilter = "all" | "mtproto" | "socks5";
type StatusFilter = "all" | "working" | "failed" | "unsupported";
type SavedProfile = {
  id: string; name: string; protocol: string; host: string; port: number;
  secret: string; username: string; password: string;
};
type ProfileSnapshot = {
  profiles: SavedProfile[];
  selected_id: string | null;
  favorite_ids: string[];
};
type HistoryEntry = {
  id: string; timestamp_ms: number; endpoint: string; protocol: string;
  action: string; success: boolean; elapsed_ms: number; detail: string;
};
type NetworkCheck = {
  host: string; tcp_443: boolean; tls_http: boolean; http_status: number | null;
  elapsed_ms: number; detail: string;
};
type DcPortCheck = {
  dc_label: string; address: string; address_family: string; source: string;
  port: number; reachable: boolean; elapsed_ms: number; detail: string;
};
type DpiProbeStatus = {
  platform: string;
  status: string;
  elevated: boolean;
  winws2_found: boolean;
  windivert_dll_found: boolean;
  windivert_driver_found: boolean;
  cygwin_runtime_found: boolean;
  runtime_started: boolean;
  resource_directory: string;
  message: string;
};
type DiagnosticsSnapshot = {
  generated_at_ms: number;
  network_checks: NetworkCheck[];
  dc_checks: DcPortCheck[];
  network_successes: number;
  network_total: number;
  dc_successes: number;
  dc_total: number;
};
type DesktopSettings = {
  auto_failover_enabled: boolean;
  check_interval_seconds: number;
  failure_threshold: number;
  catalog_refresh_minutes: number;
  failed_profile_cooldown_seconds: number;
  switch_cooldown_seconds: number;
  auto_optimize_connection: boolean;
  cloudflare_websocket_enabled: boolean;
  direct_websocket_first: boolean;
  websocket_ping_interval_seconds: number;
  community_feedback_enabled: boolean;
  community_feedback_preference_set: boolean;
  telegram_proxy_link_fingerprint: string | null;
};
type ProfileForm = {
  id: string; name: string; protocol: "MTProto" | "SOCKS5"; host: string; port: string;
  secret: string; username: string; password: string;
};

const nav: { id: Section; label: string; icon: typeof LayoutDashboard }[] = [
  { id: "overview", label: "Обзор", icon: LayoutDashboard },
  { id: "catalog", label: "Прокси-каталог", icon: Network },
  { id: "selector", label: "Автоподбор", icon: SlidersHorizontal },
  { id: "profiles", label: "Мои прокси", icon: Bookmark },
  { id: "diagnostics", label: "Диагностика", icon: Activity },
  { id: "favorites", label: "Избранное", icon: Heart },
  { id: "history", label: "История", icon: History }
];
const initialStatus: EngineStatus = {
  engine_ready: false, upstream_healthy: false, telegram_connected: false,
  endpoint: "127.0.0.1:1443", selected_upstream: null,
  current_route: "Движок остановлен", active_connections: 0, last_error: null,
  connections_total: 0, websocket_connections: 0, cloudflare_connections: 0,
  tcp_fallback_connections: 0, failed_connections: 0, bytes_up: 0, bytes_down: 0,
  uptime_seconds: 0, last_check_epoch_ms: null, last_check_latency_ms: null,
  failure_streak: 0, switch_count: 0, standby_count: 0, standby_scanning: false
};

function App() {
  const [section, setSection] = useState<Section>("overview");
  const [sidebarExpanded, setSidebarExpanded] = useState(false);
  const [appVersion, setAppVersion] = useState("0.4.2");
  const [rnmdAccount, setRnmdAccount] = useState<RnmdAccount | null>(null);
  const [rnmdBusy, setRnmdBusy] = useState(false);
  const [rnmdLoading, setRnmdLoading] = useState(true);
  const [rnmdError, setRnmdError] = useState<string | null>(null);
  const [status, setStatus] = useState<EngineStatus>(initialStatus);
  const [checking, setChecking] = useState(false);
  const [backendAvailable, setBackendAvailable] = useState(false);
  const [catalog, setCatalog] = useState<CatalogResponse | null>(null);
  const [catalogLoading, setCatalogLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [profiles, setProfiles] = useState<ProfileSnapshot>({ profiles: [], selected_id: null, favorite_ids: [] });
  const [profilesLoading, setProfilesLoading] = useState(false);
  const [profileForm, setProfileForm] = useState<ProfileForm | null>(null);
  const [profileBusy, setProfileBusy] = useState<string | null>(null);
  const [historyEntries, setHistoryEntries] = useState<HistoryEntry[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [diagnostics, setDiagnostics] = useState<DiagnosticsSnapshot | null>(null);
  const [dpiProbe, setDpiProbe] = useState<DpiProbeStatus | null>(null);
  const [dpiProbeLoading, setDpiProbeLoading] = useState(false);
  const [diagnosticsRunning, setDiagnosticsRunning] = useState(false);
  const [desktopSettings, setDesktopSettings] = useState<DesktopSettings | null>(null);
  const [settingsLoading, setSettingsLoading] = useState(false);
  const [settingsSaving, setSettingsSaving] = useState(false);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [typeFilter, setTypeFilter] = useState<TypeFilter>("all");
  const [visibleCount, setVisibleCount] = useState(60);
  const [testResults, setTestResults] = useState<Record<string, ProxyTestResult>>({});
  const [testing, setTesting] = useState<Record<string, boolean>>({});
  const [connecting, setConnecting] = useState<string | null>(null);
  const [engineBusy, setEngineBusy] = useState(false);
  const [autoResult, setAutoResult] = useState<AutoSelectionResult | null>(null);
  const [autoProgress, setAutoProgress] = useState<AutoSelectionProgress>(initialAutoProgress);
  const [notice, setNotice] = useState<{ kind: "info" | "success" | "error"; text: string } | null>(null);
  const startupAutoSelectionStarted = useRef(false);

  const defaultSettings: DesktopSettings = {
    auto_failover_enabled: true,
    check_interval_seconds: 60,
    failure_threshold: 3,
    catalog_refresh_minutes: 5,
    failed_profile_cooldown_seconds: 600,
    switch_cooldown_seconds: 90,
    auto_optimize_connection: true,
    cloudflare_websocket_enabled: true,
    direct_websocket_first: true,
    websocket_ping_interval_seconds: 180,
    community_feedback_enabled: true,
    community_feedback_preference_set: true,
    telegram_proxy_link_fingerprint: null
  };

  async function reloadRnmd() {
    setRnmdLoading(true);
    setRnmdError(null);
    try {
      setRnmdAccount(await invoke<RnmdAccount | null>("rnmd_status"));
    } catch (error) {
      setRnmdError(String(error));
    } finally {
      setRnmdLoading(false);
    }
  }

  async function loginRnmd() {
    setRnmdBusy(true);
    setRnmdError(null);
    try {
      setRnmdAccount(await invoke<RnmdAccount>("rnmd_login"));
      setNotice({ kind: "success", text: "Вход в RNMD ID выполнен." });
    } catch (error) {
      setRnmdError(String(error));
    } finally {
      setRnmdBusy(false);
    }
  }

  async function logoutRnmd() {
    setRnmdBusy(true);
    try {
      await invoke("rnmd_logout");
      setRnmdAccount(null);
      setRnmdError(null);
      setNotice({ kind: "info", text: "Вы вышли из RNMD ID. TGU продолжает работать без аккаунта." });
    } catch (error) {
      setRnmdError(String(error));
    } finally {
      setRnmdBusy(false);
    }
  }

  useEffect(() => {
    void getVersion().then(setAppVersion).catch(() => {});
    void reloadRnmd();
  }, []);

  function formatBytes(value: number): string {
    if (value < 1024) return `${value} B`;
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
    if (value < 1024 * 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(1)} MB`;
    return `${(value / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  }

  function formatDuration(seconds: number): string {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const rest = seconds % 60;
    return hours > 0 ? `${hours} ч ${minutes} мин` : minutes > 0 ? `${minutes} мин ${rest} с` : `${rest} с`;
  }

  async function loadProfiles() {
    setProfilesLoading(true);
    try {
      setProfiles(await invoke<ProfileSnapshot>("profiles_load"));
    } catch (error) {
      setNotice({ kind: "error", text: `Не удалось открыть защищённые профили: ${String(error)}` });
    } finally {
      setProfilesLoading(false);
    }
  }

  async function loadHistory() {
    setHistoryLoading(true);
    try {
      setHistoryEntries(await invoke<HistoryEntry[]>("history_load"));
    } catch (error) {
      setNotice({ kind: "error", text: `Не удалось загрузить историю: ${String(error)}` });
    } finally {
      setHistoryLoading(false);
    }
  }

  async function loadSettings() {
    setSettingsLoading(true);
    try {
      setDesktopSettings(await invoke<DesktopSettings>("settings_load"));
    } catch (error) {
      setNotice({ kind: "error", text: `Не удалось загрузить настройки: ${String(error)}` });
      setDesktopSettings(defaultSettings);
    } finally {
      setSettingsLoading(false);
    }
  }

  async function refreshStatus() {
    setChecking(true);
    try {
      setStatus(await invoke<EngineStatus>("engine_status"));
      setBackendAvailable(true);
    } catch {
      setBackendAvailable(false);
    } finally {
      setChecking(false);
    }
  }

  async function runDpiProbe() {
    setDpiProbeLoading(true);
    try {
      const result = await invoke<DpiProbeStatus>("dpi_probe");
      setDpiProbe(result);
      setNotice({ kind: result.status === "ready_for_runtime" ? "success" : "info", text: result.message });
    } catch (error) {
      setNotice({ kind: "error", text: `Проверка TGU DPI не удалась: ${String(error)}` });
    } finally {
      setDpiProbeLoading(false);
    }
  }

  async function loadCatalog(refresh: boolean): Promise<CatalogResponse | null> {
    setCatalogLoading(true);
    setCatalogError(null);
    try {
      const result = await invoke<CatalogResponse>(refresh ? "catalog_refresh" : "catalog_load");
      setCatalog(result);
      if (result.warning) setNotice({ kind: "info", text: result.warning });
      else if (refresh) setNotice({ kind: "success", text: `Каталог обновлён: ${result.count} актуальных записей.` });
      return result;
    } catch (error) {
      const message = String(error);
      setCatalogError(message);
      setNotice({ kind: "error", text: message });
      return null;
    } finally {
      setCatalogLoading(false);
    }
  }

  async function testProxy(proxy: ProxySummary) {
    setTesting(previous => ({ ...previous, [proxy.id]: true }));
    setTestResults(previous => { const next = { ...previous }; delete next[proxy.id]; return next; });
    setNotice({ kind: "info", text: `Проверяю ${proxy.server}:${proxy.port} через Telegram DC2…` });
    try {
      const result = await invoke<ProxyTestResult>("proxy_test", { proxyId: proxy.id });
      setTestResults(previous => ({ ...previous, [proxy.id]: result }));
      setNotice({
        kind: result.success ? "success" : "error",
        text: result.success ? `${proxy.server} прошёл проверку Telegram DC2 за ${result.elapsed_ms} мс.` : `${proxy.server}: ${result.detail}`
      });
    } catch (error) {
      const result = { success: false, stage: "Proxy check", detail: String(error), elapsed_ms: 0 };
      setTestResults(previous => ({ ...previous, [proxy.id]: result }));
      setNotice({ kind: "error", text: result.detail });
    } finally {
      setTesting(previous => ({ ...previous, [proxy.id]: false }));
    }
  }

  async function connectThroughTgu(proxy: ProxySummary) {
    setConnecting(proxy.id);
    setEngineBusy(true);
    try {
      // Changing an upstream intentionally restarts the local listener. The
      // local MTProto address remains 127.0.0.1:1443 in Telegram.
      const current = await invoke<EngineStatus>("engine_status");
      if (current.engine_ready) await invoke("engine_stop");
      const verified = await invoke<ProxyTestResult>("engine_select_upstream", { proxyId: proxy.id });
      setTestResults(previous => ({ ...previous, [proxy.id]: verified }));
      await invoke<EngineStatus>("engine_start");
      const telegramLinkOpened = await invoke<boolean>("engine_open_in_telegram_if_needed");
      await Promise.all([refreshStatus(), loadProfiles(), loadHistory()]);
      setNotice({
        kind: "success",
        text: telegramLinkOpened
          ? "Upstream проверен, локальный прокси TGU запущен. Первая ссылка передана Telegram Desktop — подтверди добавление прокси."
          : "Upstream проверен, локальный прокси TGU запущен. Ссылка уже передавалась ранее, повторное окно Telegram не открывалось."
      });
    } catch (error) {
      await refreshStatus();
      setNotice({ kind: "error", text: String(error) });
    } finally {
      setConnecting(null);
      setEngineBusy(false);
    }
  }

  async function startAutoRoute() {
    setEngineBusy(true);
    setAutoResult(null);
    setNotice({ kind: "info", text: "Подбираю прокси по реальному ответу Telegram DC из текущей сети…" });
    try {
      const current = await invoke<EngineStatus>("engine_status");
      if (current.engine_ready) await invoke("engine_stop");

      const selection = await invoke<AutoSelectionResult>("engine_auto_select");
      setAutoResult(selection);

      // A failed new selection must not erase an existing working profile or
      // silently change its routing policy. The previous upstream is retained.
      await invoke<EngineStatus>("engine_start");
      const telegramLinkOpened = await invoke<boolean>("engine_open_in_telegram_if_needed");
      await Promise.all([refreshStatus(), loadProfiles(), loadHistory()]);
      const telegramNote = telegramLinkOpened
        ? " Первая ссылка на локальный прокси передана Telegram Desktop — подтверди добавление."
        : " Локальный прокси уже передавался Telegram ранее; повторное окно не открывалось.";
      const feedbackNote = selection.community_feedback?.message
        ? ` ${selection.community_feedback.message}`
        : " CN: статус отправки не получен.";
      setNotice({
        kind: selection.selected ? "success" : "info",
        text: selection.selected
          ? `Выбран ${selection.selected.protocol} ${selection.selected.server}:${selection.selected.port} · ${selection.verified_latency_ms ?? "—"} мс.${telegramNote}${feedbackNote}`
          : `${selection.message}${telegramNote}${feedbackNote} Пока upstream не выбран, движок использует доступные резервные маршруты.`
      });
    } catch (error) {
      await refreshStatus();
      setNotice({ kind: "error", text: String(error) });
    } finally {
      setEngineBusy(false);
    }
  }

  async function applyProxyToTelegram() {
    try {
      await invoke("engine_open_in_telegram");
      setNotice({ kind: "success", text: "Локальная ссылка TGU передана Telegram Desktop. Подтверди добавление прокси в окне Telegram." });
    } catch (error) {
      setNotice({ kind: "error", text: String(error) });
    }
  }

  async function stopEngine() {
    setEngineBusy(true);
    try {
      setStatus(await invoke<EngineStatus>("engine_stop"));
      setNotice({ kind: "info", text: "Локальный MTProto endpoint остановлен. Telegram больше не сможет использовать локальный маршрут TGU." });
    } catch (error) {
      setNotice({ kind: "error", text: String(error) });
    } finally {
      setEngineBusy(false);
    }
  }
  async function startEngine() {
    setEngineBusy(true);
    try {
      setStatus(await invoke<EngineStatus>("engine_start"));
      const telegramLinkOpened = await invoke<boolean>("engine_open_in_telegram_if_needed");
      await refreshStatus();
      await loadProfiles();
      setNotice({
        kind: "success",
        text: telegramLinkOpened
          ? "Локальный MTProto запущен. Первая ссылка на 127.0.0.1:1443 передана Telegram Desktop — подтверди добавление прокси."
          : "Локальный MTProto запущен. Ссылка уже передавалась ранее, повторное окно Telegram не открывалось."
      });
    } catch (error) {
      setNotice({ kind: "error", text: String(error) });
    } finally {
      setEngineBusy(false);
    }
  }

  function openNewProfile() {
    setProfileForm({
      id: crypto.randomUUID(),
      name: "",
      protocol: "MTProto",
      host: "",
      port: "443",
      secret: "",
      username: "",
      password: ""
    });
  }

  function openEditProfile(profile: SavedProfile) {
    setProfileForm({
      ...profile,
      protocol: profile.protocol.toLowerCase().includes("socks") ? "SOCKS5" : "MTProto",
      port: String(profile.port)
    });
  }

  async function saveProfile() {
    if (!profileForm) return;
    setProfileBusy(profileForm.id);
    try {
      const profile: SavedProfile = {
        ...profileForm,
        port: Number(profileForm.port)
      };
      const updated = await invoke<ProfileSnapshot>("profile_save", { profile });
      setProfiles(updated);
      setProfileForm(null);
      setNotice({ kind: "success", text: `Профиль «${profile.name}» сохранён в зашифрованном хранилище Windows.` });
    } catch (error) {
      setNotice({ kind: "error", text: String(error) });
    } finally {
      setProfileBusy(null);
    }
  }

  async function testSavedProfile(profile: SavedProfile) {
    setProfileBusy(profile.id);
    try {
      const result = await invoke<ProxyTestResult>("profile_test", { profileId: profile.id });
      await loadHistory();
      setNotice({
        kind: result.success ? "success" : "error",
        text: result.success
          ? `${profile.host} прошёл Telegram DC2 за ${result.elapsed_ms} мс.`
          : `${profile.host}: ${result.detail}`
      });
    } catch (error) {
      setNotice({ kind: "error", text: String(error) });
    } finally {
      setProfileBusy(null);
    }
  }

  async function selectSavedProfile(profile: SavedProfile) {
    setProfileBusy(profile.id);
    setEngineBusy(true);
    try {
      const current = await invoke<EngineStatus>("engine_status");
      if (current.engine_ready) await invoke("engine_stop");
      const result = await invoke<ProxyTestResult>("profile_select", { profileId: profile.id });
      await invoke<EngineStatus>("engine_start");
      await Promise.all([refreshStatus(), loadProfiles(), loadHistory()]);
      setNotice({ kind: "success", text: `Выбран и повторно проверен ${profile.protocol} · ${profile.host}:${profile.port} · ${result.elapsed_ms} мс. Telegram не изменён.` });
    } catch (error) {
      await refreshStatus();
      setNotice({ kind: "error", text: String(error) });
    } finally {
      setProfileBusy(null);
      setEngineBusy(false);
    }
  }

  async function deleteSavedProfile(profile: SavedProfile) {
    setProfileBusy(profile.id);
    try {
      const current = await invoke<EngineStatus>("engine_status");
      if (current.engine_ready && profiles.selected_id === profile.id) await invoke("engine_stop");
      const updated = await invoke<ProfileSnapshot>("profile_delete", { profileId: profile.id });
      setProfiles(updated);
      await refreshStatus();
      setNotice({ kind: "success", text: `Профиль «${profile.name}» удалён.` });
    } catch (error) {
      setNotice({ kind: "error", text: String(error) });
    } finally {
      setProfileBusy(null);
    }
  }

  async function clearSelectedProfile() {
    setEngineBusy(true);
    try {
      const current = await invoke<EngineStatus>("engine_status");
      if (current.engine_ready) await invoke("engine_stop");
      await invoke("profile_clear");
      await Promise.all([loadProfiles(), refreshStatus()]);
      setNotice({ kind: "info", text: "Внешний upstream очищен. Следующий запуск использует AUTO transport fallback." });
    } catch (error) {
      setNotice({ kind: "error", text: String(error) });
    } finally {
      setEngineBusy(false);
    }
  }

  async function toggleFavorite(proxy: ProxySummary) {
    try {
      const updated = await invoke<ProfileSnapshot>("favorite_toggle", { proxyId: proxy.id });
      setProfiles(updated);
    } catch (error) {
      setNotice({ kind: "error", text: String(error) });
    }
  }

  async function saveDesktopSettings() {
    if (!desktopSettings) return;
    setSettingsSaving(true);
    try {
      const updated = await invoke<DesktopSettings>("settings_save", { settings: desktopSettings });
      setDesktopSettings(updated);
      setNotice({ kind: "success", text: "Параметры мониторинга и маршрутов сохранены." });
    } catch (error) {
      setNotice({ kind: "error", text: String(error) });
    } finally {
      setSettingsSaving(false);
    }
  }

  async function clearHistory() {
    try {
      await invoke("history_clear");
      await loadHistory();
      setNotice({ kind: "success", text: "История проверок очищена." });
    } catch (error) {
      setNotice({ kind: "error", text: String(error) });
    }
  }
  async function runDiagnostics() {
    setDiagnosticsRunning(true);
    try {
      const result = await invoke<DiagnosticsSnapshot>("diagnostics_run");
      setDiagnostics(result);
      setNotice({ kind: "success", text: `Диагностика завершена: TLS/HTTP ${result.network_successes}/${result.network_total}, Telegram DC TCP ${result.dc_successes}/${result.dc_total}.` });
    } catch (error) {
      setNotice({ kind: "error", text: String(error) });
    } finally {
      setDiagnosticsRunning(false);
    }
  }

  useEffect(() => {
    let unlisten: (() => void) | undefined;
    let disposed = false;
    void listen<AutoSelectionProgress>("auto-selection-progress", event => {
      setAutoProgress(event.payload);
    }).then(stop => {
      if (disposed) stop();
      else unlisten = stop;
    });
    return () => {
      disposed = true;
      unlisten?.();
    };
  }, []);

  useEffect(() => {
    let disposed = false;

    const bootstrap = async () => {
      await Promise.all([refreshStatus(), loadProfiles(), loadHistory(), loadSettings()]);
      const loadedCatalog = await loadCatalog(false);
      if (disposed || !loadedCatalog || startupAutoSelectionStarted.current) return;

      startupAutoSelectionStarted.current = true;
      try {
        const current = await invoke<EngineStatus>("engine_status");
        if (disposed || current.engine_ready) return;

        setNotice({ kind: "info", text: "TGU автоматически подбирает рабочий маршрут по сети этого компьютера…" });
        const selection = await invoke<AutoSelectionResult>("engine_auto_select");
        if (disposed) return;

        setAutoResult(selection);
        const fastConnected = selection.selected != null && selection.message.startsWith("Fast Connect:");
        let telegramLinkOpened = false;
        if (fastConnected) {
          await invoke<EngineStatus>("engine_start");
          telegramLinkOpened = await invoke<boolean>("engine_open_in_telegram_if_needed");
        }
        await Promise.all([loadProfiles(), loadHistory(), refreshStatus()]);
        if (disposed) return;
        setNotice({
          kind: selection.selected ? "success" : "info",
          text: fastConnected
            ? `Fast Connect: ${selection.selected?.server}:${selection.selected?.port} проверен на ПК, TGU запущен. ${telegramLinkOpened ? "Подтверди прокси в Telegram." : "Ранее добавленный прокси готов."} Резерв пополняется в фоне.`
            : selection.selected
              ? `Автоподбор завершён: ${selection.selected.server}:${selection.selected.port} · ${selection.verified_latency_ms ?? "—"} мс. Нажми «Запустить TGU», чтобы запустить локальный прокси.`
              : `Автоподбор завершён: ${selection.message}`
        });
      } catch (error) {
        if (!disposed) setNotice({ kind: "error", text: `Автоподбор при запуске не удался: ${String(error)}` });
      }
    };

    void bootstrap();
    const refreshTimer = window.setInterval(() => void refreshStatus(), 2500);
    return () => {
      disposed = true;
      window.clearInterval(refreshTimer);
    };
  }, []);

  const title = nav.find(item => item.id === section)?.label ??
    (section === "settings" ? "Настройки" : section === "account" ? "RNMD ID" : "О программе");
  const filteredProxies = useMemo(() => {
    if (!catalog) return [];
    const term = search.trim().toLowerCase();
    return [...catalog.proxies].filter(proxy => {
      const protocol = proxy.protocol.toLowerCase();
      const local = testResults[proxy.id];
      const matchesType = typeFilter === "all" || (typeFilter === "mtproto" ? protocol.includes("mt") : protocol.includes("socks"));
      const matchesStatus = statusFilter === "all"
        || (statusFilter === "working" && (proxy.source_verified || local?.success === true))
        || (statusFilter === "failed" && local != null && !local.success)
        || (statusFilter === "unsupported" && !proxy.supported);
      const matchesSearch = !term || proxy.server.toLowerCase().includes(term) || (proxy.country ?? "").toLowerCase().includes(term) || protocol.includes(term);
      return matchesType && matchesStatus && matchesSearch;
    }).sort((a, b) => {
      if (a.supported !== b.supported) return a.supported ? -1 : 1;
      if (a.ru_verified !== b.ru_verified) return a.ru_verified ? -1 : 1;
      if (a.ru_verified && b.ru_verified) return (a.ru_rtt_ms ?? Infinity) - (b.ru_rtt_ms ?? Infinity);
      return (a.source_rtt_ms ?? Number.MAX_SAFE_INTEGER) - (b.source_rtt_ms ?? Number.MAX_SAFE_INTEGER);
    });
  }, [catalog, search, typeFilter, statusFilter, testResults]);
  const visibleProxies = filteredProxies.slice(0, visibleCount);

  return <div className="desktop-frame">
    <header className="window-titlebar" role="banner">
      <div className="titlebar-drag" data-tauri-drag-region aria-label="Переместить окно TGU Desktop">
        <span className="titlebar-brand" aria-hidden="true"><img src="/tgu-icon.svg" alt=""/></span>
        <span className="titlebar-name" aria-hidden="true">TGU<span className="titlebar-name-secondary">DESKTOP</span></span>
        <span className="titlebar-version" aria-hidden="true">v{appVersion}</span>
        <span className="titlebar-divider" aria-hidden="true"/>
        <span className="titlebar-subtitle" aria-hidden="true">RNMDTEAM / SECURE CONNECTIVITY</span>
      </div>
      <div className="window-controls" role="group" aria-label="Управление окном">
        <button type="button" className="window-control" aria-label="Свернуть окно" title="Свернуть" onClick={() => void getCurrentWindow().minimize().catch(() => setNotice({kind:"error",text:"Не удалось свернуть окно."}))}><Minus size={15}/></button>
        <button type="button" className="window-control" aria-label="Развернуть или восстановить окно" title="Развернуть / восстановить" onClick={() => void getCurrentWindow().toggleMaximize().catch(() => setNotice({kind:"error",text:"Не удалось изменить размер окна."}))}><Square size={13}/></button>
        <button type="button" className="window-control close" aria-label="Свернуть TGU в системный трей" title="Скрыть в трей — TGU продолжит работать" onClick={() => void getCurrentWindow().hide().catch(() => setNotice({kind:"error",text:"Не удалось скрыть TGU в системный трей."}))}><X size={17}/></button>
      </div>
    </header>
    <div className="app-shell">
    <aside className={sidebarExpanded ? "sidebar expanded" : "sidebar collapsed"} aria-label="Навигация TGU">
      <div className="sidebar-head">
        <div className="brand"><img className="brand-mark" src="/tgu-icon.svg" alt="TGU"/><div className="brand-caption"><b>TGU</b><span>DESKTOP</span></div></div>
        <button className="sidebar-toggle" type="button" onClick={() => setSidebarExpanded(v => !v)}
          aria-label={sidebarExpanded ? "Свернуть боковую панель" : "Развернуть боковую панель"}
          title={sidebarExpanded ? "Свернуть меню" : "Развернуть меню"} aria-expanded={sidebarExpanded}>
          {sidebarExpanded ? <PanelLeftClose size={18}/> : <PanelLeftOpen size={18}/>}
        </button>
      </div>
      <div className="nav-label">РАБОЧЕЕ ПРОСТРАНСТВО</div>
      <nav className="sidebar-nav-scroll">{nav.map(item => { const Icon = item.icon; return <button key={item.id} type="button"
        title={!sidebarExpanded ? item.label : undefined} aria-label={item.label}
        aria-current={section === item.id ? "page" : undefined}
        className={section === item.id ? "nav-item active" : "nav-item"}
        onClick={() => setSection(item.id)}><Icon className="nav-icon" size={19} strokeWidth={1.8}/><span className="nav-text">{item.label}</span></button>; })}</nav>
      <div className="sidebar-bottom">
        <button type="button" title={!sidebarExpanded ? "RNMD ID" : undefined} aria-label="RNMD ID" className={section === "account" ? "nav-item active" : "nav-item"} onClick={() => setSection("account")}>
          <UserRound className="nav-icon" size={19}/><span className="nav-text">{rnmdAccount?.display_name || "RNMD ID"}</span></button>
        <button type="button" title={!sidebarExpanded ? "Настройки" : undefined} aria-label="Настройки" className={section === "settings" ? "nav-item active" : "nav-item"} onClick={() => setSection("settings")}>
          <Settings className="nav-icon" size={19}/><span className="nav-text">Настройки</span></button>
        <button type="button" title={!sidebarExpanded ? "О программе" : undefined} aria-label="О программе" className={section === "about" ? "nav-item active" : "nav-item"} onClick={() => setSection("about")}>
          <CircleHelp className="nav-icon" size={19}/><span className="nav-text">О программе</span></button>
        <div className="sidebar-version" title={`TGU Desktop v${appVersion}`}>
          <span className="version-dot"/><span className="version-name">TGU Desktop</span><span className="version-number">v{appVersion}</span>
        </div>
      </div>
    </aside>
    <main className="main">
      <header className="topbar">
        <div className="breadcrumbs">TGU Desktop <ChevronRight size={13}/><span>{title}</span></div>
        <div className="topbar-right">
          <div className={status.upstream_healthy ? "engine-indicator online" : status.engine_ready ? "engine-indicator running" : "engine-indicator"} title="Состояние локального прокси TGU, независимо от RNMD ID">
            <span className="indicator-dot" aria-hidden="true"/>
            <span>{status.upstream_healthy ? "ПРОКСИ РАБОТАЕТ" : status.engine_ready ? "TGU ЗАПУЩЕН" : "TGU ОСТАНОВЛЕН"}</span>
          </div>
          <button type="button" className={rnmdAccount ? "account-indicator signed-in" : "account-indicator"} onClick={() => setSection("account")}
            title={rnmdAccount ? "Открыть профиль RNMD ID" : "Войти в RNMD ID"} aria-label={rnmdAccount ? "RNMD ID: аккаунт подключён, открыть профиль" : "RNMD ID: вход не выполнен"}>
            <span className="account-indicator-text">
              <strong>{rnmdLoading ? "RNMD ID" : rnmdAccount ? "RNMD ID · ВОШЁЛ" : "RNMD ID · ГОСТЬ"}</strong>
              {rnmdAccount && <small>{rnmdAccount.telegram_username ? `@${rnmdAccount.telegram_username}` : rnmdAccount.display_name}</small>}
            </span>
            <span className="topbar-account">
              {rnmdAccount?.avatar_url ? <img src={rnmdAccount.avatar_url} alt="" referrerPolicy="no-referrer"/> : <UserRound size={16}/>}
            </span>
          </button>
        </div>
      </header>
      <div className="page">
        {section === "overview" ? <>
          <div className="page-heading"><div><div className="eyebrow">ПАНЕЛЬ УПРАВЛЕНИЯ</div><h1>Обзор</h1><p>Локальный MTProto relay и внешний маршрут Telegram.</p></div><button className="icon-button" title="Обновить состояние" onClick={() => void refreshStatus()}><RefreshCw size={17} className={checking ? "spin" : ""}/></button></div>
          <section className="status-card">
            <div className="status-main"><div className="status-symbol"><Power size={22}/></div><div><div className="eyebrow">ЛОКАЛЬНЫЙ MTProto</div><h2>{status.engine_ready ? "Engine Ready" : "Engine Stopped"}</h2><p>{status.engine_ready ? `Слушает ${status.endpoint}` : "Запусти локальный endpoint для Telegram Desktop."}</p></div></div>
            {status.engine_ready
              ? <button className="button secondary" disabled={engineBusy} onClick={() => void stopEngine()}><Power size={16}/>{engineBusy ? "Остановка…" : "Остановить"}</button>
              : <button className="button primary" disabled={engineBusy} onClick={() => void startEngine()}><Power size={16}/>{engineBusy ? "Запуск…" : "Запустить TGU"}</button>}
          </section>
          {autoProgress.is_running && <section className="panel auto-progress-panel">
            <div className="panel-heading">
              <div><h3>Автоподбор выполняется</h3><p>{autoProgress.phase}</p></div>
              <strong className="auto-progress-count">{autoProgress.completed.toLocaleString("ru-RU")} / {autoProgress.total.toLocaleString("ru-RU")}</strong>
            </div>
            <div className="auto-progress-track"><span style={{ width: `${autoProgress.total > 0 ? Math.min(100, autoProgress.completed / autoProgress.total * 100) : 0}%` }}/></div>
            <div className="auto-progress-metrics">
              <div><span>ПРОВЕРЕНО</span><b>{autoProgress.completed.toLocaleString("ru-RU")}</b></div>
              <div><span>ДОСТУПНО</span><b>{autoProgress.available.toLocaleString("ru-RU")}</b></div>
              <div><span>ОСТАЛОСЬ</span><b>{Math.max(0, autoProgress.total - autoProgress.completed).toLocaleString("ru-RU")}</b></div>
              <div><span>ВРЕМЯ</span><b>{(autoProgress.elapsed_ms / 1000).toFixed(1)} с</b></div>
            </div>
            {autoProgress.current_endpoint && <div className="auto-progress-line"><span>СЕЙЧАС</span><b>{autoProgress.current_endpoint}</b></div>}
            {autoProgress.best_endpoint && <div className="auto-progress-line"><span>ЛИДЕР</span><b>{autoProgress.best_endpoint} · {autoProgress.best_latency_ms ?? "—"} мс</b></div>}
          </section>}
          <div className="section-title"><h3>Состояние системы</h3><span>ФАКТИЧЕСКИЙ СТАТУС BACKEND</span></div>
          <div className="metric-grid">
            <div className="metric-card"><div className="metric-top"><span>LOCAL ENGINE</span><Server size={16}/></div><strong>{status.engine_ready ? "Ready" : "Stopped"}</strong><small>{status.engine_ready ? status.endpoint : "Локальный listener остановлен"}</small></div>
            <div className="metric-card"><div className="metric-top"><span>UPSTREAM</span><Wifi size={16}/></div><strong>{status.upstream_healthy ? "Healthy" : "Waiting"}</strong><small>{status.current_route || "Маршрут ещё не открыт"}</small><small>Резерв: {status.standby_count}/5 · {status.standby_scanning ? "проверка в фоне" : "ожидание / готов"}</small></div>
            <div className="metric-card"><div className="metric-top"><span>TELEGRAM DC</span><ShieldCheck size={16}/></div><strong className="metric-text">{status.telegram_connected ? "Responding" : "Не подтверждено"}</strong><small>{status.active_connections} активных локальных сессий</small></div>
          </div>
          <div className="metric-grid traffic-grid">
            <div className="metric-card"><div className="metric-top"><span>TRAFFIC UP</span><Activity size={16}/></div><strong>{formatBytes(status.bytes_up)}</strong><small>От Telegram Desktop к upstream</small></div>
            <div className="metric-card"><div className="metric-top"><span>TRAFFIC DOWN</span><Activity size={16}/></div><strong>{formatBytes(status.bytes_down)}</strong><small>От upstream к Telegram Desktop</small></div>
            <div className="metric-card"><div className="metric-top"><span>SESSIONS</span><Network size={16}/></div><strong>{status.connections_total.toLocaleString("ru-RU")}</strong><small>{formatDuration(status.uptime_seconds)} · {status.failed_connections} ошибок</small></div>
          </div>
          <div className="content-grid">
            <section className="panel"><div className="panel-heading"><div><h3>Маршрут подключения</h3><p>Telegram подключается к локальному прокси TGU</p></div><span className={status.engine_ready ? "state-pill on" : "state-pill"}>{status.engine_ready ? "LISTENING" : "STOPPED"}</span></div>
              <div className="connection-row"><div className="connection-icon"><Network size={18}/></div><div className="connection-info"><b>Local MTProto endpoint</b><span>{status.endpoint} · секрет генерируется локально</span></div><span className="muted">{status.engine_ready ? <CheckCircle2 size={16}/> : "—"}</span></div>
              <div className="connection-row"><div className="connection-icon"><Globe2 size={18}/></div><div className="connection-info"><b>Selected upstream</b><span>{status.selected_upstream || "AUTO · Telegram WS → Cloudflare WS → TCP"}</span></div><span className="muted">L3</span></div>
              {status.last_error && <div className="proxy-unsupported">{status.last_error}</div>}
              <div className="panel-footer"><span>{backendAvailable ? "Rust relay backend отвечает." : "Backend пока недоступен."} Ответ DC появляется после реального трафика.</span><button className="text-button" onClick={() => setSection("diagnostics")}>Диагностика <ChevronRight size={14}/></button></div>
            </section>
            <section className="panel quick-panel"><div className="panel-heading"><div><h3>Быстрые действия</h3><p>Инструменты подключения</p></div></div>
              <button className="quick-action" onClick={() => setSection("catalog")}><span><Network size={17}/></span><div><b>Выбрать внешний прокси</b><small>Проверить MTProto / SOCKS5</small></div><ChevronRight size={16}/></button>
              <button className="quick-action" disabled={!status.engine_ready} onClick={() => void applyProxyToTelegram()}><span><ExternalLink size={17}/></span><div><b>Повторно открыть Proxy в Telegram</b><small>Используй, если удалил локальный прокси из Telegram</small></div><ChevronRight size={16}/></button>
              <button className="quick-action" onClick={() => void loadCatalog(true)}><span><RefreshCw size={17}/></span><div><b>Обновить каталог</b><small>Загрузить свежие узлы</small></div><ChevronRight size={16}/></button>
              <button className="quick-action" onClick={() => setSection("selector")}><span><SlidersHorizontal size={17}/></span><div><b>Автоподбор</b><small>Пока доступен ручной выбор</small></div><ChevronRight size={16}/></button>
            </section>
          </div>
        </> : section === "catalog" ? <>
          <div className="page-heading"><div><div className="eyebrow">TGU PROXY DIRECTORY</div><h1>Прокси-каталог</h1><p>Узел проверяется локально, затем используется как внешний маршрут TGU.</p></div><button className="button secondary" disabled={catalogLoading} onClick={() => void loadCatalog(true)}><RefreshCw size={15} className={catalogLoading ? "spin" : ""}/>{catalogLoading ? "Обновление…" : "Обновить каталог"}</button></div>
          <div className="catalog-summary">
            <div><span>АКТУАЛЬНЫХ ЗАПИСЕЙ</span><strong>{catalog ? catalog.count.toLocaleString("ru-RU") : "—"}</strong></div>
            <div><span>ИСТОЧНИК</span><strong>{catalog?.source.includes("freedomchecker") ? "Freedom Checker" : "GitHub Raw"}</strong></div>
            <div><span>СНИМОК</span><strong>{catalog?.generated_at ? new Date(catalog.generated_at).toLocaleString("ru-RU") : "—"}</strong></div>
            <div><span>КЭШ</span><strong>{catalog ? (catalog.from_cache ? "Локальный" : "Обновлён") : "—"}</strong></div>
          </div>
          {catalog?.warning && <div className="catalog-notice"><Activity size={15}/><span>{catalog.warning}</span></div>}
          {notice && <div className={notice.kind === "success" ? "catalog-notice success" : notice.kind === "error" ? "catalog-notice error" : "catalog-notice"}>{notice.kind === "success" ? <CheckCircle2 size={15}/> : notice.kind === "error" ? <XCircle size={15}/> : <Activity size={15}/>}<span>{notice.text}</span><button aria-label="Закрыть" onClick={() => setNotice(null)}><XCircle size={14}/></button></div>}
          {catalogError && <div className="catalog-notice error"><XCircle size={15}/><span>{catalogError}</span></div>}
          <div className="catalog-toolbar">
            <div className="catalog-search"><Search size={16}/><input value={search} onChange={event => { setSearch(event.target.value); setVisibleCount(60); }} placeholder="Поиск по адресу, стране или протоколу…"/></div>
            <div className="filter-row">
              <button className={typeFilter === "all" ? "filter-chip active" : "filter-chip"} onClick={() => { setTypeFilter("all"); setVisibleCount(60); }}>Все</button>
              <button className={typeFilter === "mtproto" ? "filter-chip active" : "filter-chip"} onClick={() => { setTypeFilter("mtproto"); setVisibleCount(60); }}>MTProto</button>
              <button className={typeFilter === "socks5" ? "filter-chip active" : "filter-chip"} onClick={() => { setTypeFilter("socks5"); setVisibleCount(60); }}>SOCKS5</button>
            </div>
          </div>
          <div className="filter-row catalog-status-filters">
            <button className={statusFilter === "all" ? "filter-chip active" : "filter-chip"} onClick={() => { setStatusFilter("all"); setVisibleCount(60); }}>Любой статус</button>
            <button className={statusFilter === "working" ? "filter-chip active" : "filter-chip"} onClick={() => { setStatusFilter(statusFilter === "working" ? "all" : "working"); setVisibleCount(60); }}>Рабочие</button>
            <button className={statusFilter === "failed" ? "filter-chip active" : "filter-chip"} onClick={() => { setStatusFilter(statusFilter === "failed" ? "all" : "failed"); setVisibleCount(60); }}>Недоступные</button>
            <button className={statusFilter === "unsupported" ? "filter-chip active" : "filter-chip"} onClick={() => { setStatusFilter(statusFilter === "unsupported" ? "all" : "unsupported"); setVisibleCount(60); }}>Не поддерживаются</button>
          </div>
          <div className="catalog-list-heading"><span>{filteredProxies.length.toLocaleString("ru-RU")} УЗЛОВ</span><span>RU VERIFIED — ПРИОРИТЕТ ИЗ YANDEX CLOUD · НЕ ЗАМЕНЯЕТ ЛОКАЛЬНУЮ ПРОВЕРКУ</span></div>
          {catalogLoading && !catalog ? <div className="empty-state"><LoaderCircle className="spin" size={22}/><b>Загружаю каталог</b><span>Получаю последнюю опубликованную версию TGU Proxy Directory.</span></div> :
            visibleProxies.length === 0 ? <div className="empty-state"><Search size={22}/><b>Ничего не найдено</b><span>Измени поисковый запрос или обнови каталог.</span></div> :
            <div className="proxy-list">{visibleProxies.map(proxy => {
              const result = testResults[proxy.id]; const isTesting = !!testing[proxy.id]; const isConnecting = connecting === proxy.id;
              return <article className="proxy-card" key={proxy.id}>
                <div className="proxy-card-main"><div className="proxy-identity"><span className="proxy-type">{proxy.protocol.toUpperCase()}</span><b>{proxy.server}:{proxy.port}</b><small>{proxy.country || "Страна неизвестна"}{proxy.ru_verified ? ` · RU Verified: ${proxy.ru_rtt_ms ?? "—"} мс (Yandex Cloud)` : ""}{proxy.source_tier ? ` · ${proxy.source_tier}` : ""}{proxy.community_unique_reporters >= 3 ? ` · Сообщество: ${proxy.community_success_rate ?? "—"}% / ${proxy.community_unique_reporters} устройств` : ""}</small></div>
                  <div className="proxy-meta"><div><span>ИСТОЧНИК RTT</span><b>{proxy.source_rtt_ms != null ? `${proxy.source_rtt_ms} мс` : "—"}</b></div><div><span>UPTIME</span><b>{proxy.uptime != null ? `${Math.round(proxy.uptime * 100)}%` : "—"}</b></div><div><span>ПРОВЕРОК</span><b>{proxy.checks ?? "—"}</b></div></div></div>
                <div className="proxy-card-bottom">
                  <div className={result ? (result.success ? "proxy-test-state success" : "proxy-test-state error") : "proxy-test-state"}>
                    {isTesting ? <><LoaderCircle size={14} className="spin"/> Проверка Telegram DC2…</> :
                      result ? (result.success ? <><CheckCircle2 size={14}/> Telegram DC2 · {result.elapsed_ms} мс</> : <><XCircle size={14}/> {result.stage}: {result.detail}</>) :
                      proxy.source_verified ? <><Globe2 size={14}/> Проверено каталогом · нужна локальная проверка</> : <><Activity size={14}/> Не проверено из этой сети</>}
                  </div>
                  <div className="proxy-actions">
                    <button className="proxy-action" title={profiles.favorite_ids.includes(proxy.id) ? "Убрать из избранного" : "В избранное"} onClick={() => void toggleFavorite(proxy)}><Star size={14} fill={profiles.favorite_ids.includes(proxy.id) ? "currentColor" : "none"}/></button>
                    <button className="proxy-action" disabled={!proxy.supported || isTesting || isConnecting || engineBusy} onClick={() => void testProxy(proxy)}>{isTesting ? <LoaderCircle size={14} className="spin"/> : <Activity size={14}/>}Проверить</button>
                    <button className="proxy-action primary" disabled={!proxy.supported || !result?.success || isTesting || isConnecting || engineBusy} onClick={() => void connectThroughTgu(proxy)}>{isConnecting ? <LoaderCircle size={14} className="spin"/> : <ExternalLink size={14}/>}Подключить через TGU</button>
                  </div>
                </div>
                {!proxy.supported && <div className="proxy-unsupported">{proxy.unsupported_reason || "Этот тип прокси пока не поддерживается."}</div>}
              </article>;
            })}</div>}
          {visibleCount < filteredProxies.length && <button className="load-more" onClick={() => setVisibleCount(value => value + 60)}>Показать ещё 60 узлов</button>}
          <div className="catalog-footnote">После выбора TGU повторно проверяет upstream, запускает локальный MTProto на 127.0.0.1:1443 и передаёт этот локальный адрес Telegram Desktop. Прокси не добавляется напрямую.</div>
        </> : section === "selector" ? <>
          <div className="page-heading">
            <div><div className="eyebrow">LOCAL NETWORK PROBING</div><h1>Автоподбор</h1><p>Проверка внешних прокси по реальному ответу Telegram DC2 с этого компьютера.</p></div>
            <button className="button primary" disabled={engineBusy || catalogLoading || !catalog} onClick={() => void startAutoRoute()}>
              {engineBusy ? <LoaderCircle size={15} className="spin"/> : <SlidersHorizontal size={15}/>}
              {engineBusy ? "Подбор маршрута…" : "Найти лучший маршрут"}
            </button>
          </div>
          <div className="catalog-summary">
            <div><span>ПРОВЕРЕНО / ВСЕГО</span><strong>{autoProgress.total > 0 ? `${autoProgress.completed.toLocaleString("ru-RU")} / ${autoProgress.total.toLocaleString("ru-RU")}` : autoResult ? `${autoResult.tested_candidates} / ${autoResult.total_candidates}` : "—"}</strong></div>
            <div><span>ДОСТУПНО ПОСЛЕ СКАНА</span><strong>{autoProgress.is_running || autoProgress.total > 0 ? autoProgress.available : autoResult?.first_pass_available ?? "—"}</strong></div>
            <div><span>ЛИДЕРЫ ПОДТВЕРЖДЕНЫ</span><strong>{autoProgress.finalists_verified || autoResult?.finalists_verified || "—"}</strong></div>
            <div><span>ЛУЧШИЙ RTT</span><strong>{autoProgress.best_latency_ms != null ? `${autoProgress.best_latency_ms} мс` : autoResult?.verified_latency_ms != null ? `${autoResult.verified_latency_ms} мс` : "—"}</strong></div>
          </div>
          {(autoProgress.is_running || autoProgress.total > 0) && <section className="panel auto-progress-panel">
            <div className="panel-heading">
              <div><h3>{autoProgress.is_running ? "Проверка прокси" : autoProgress.phase}</h3><p>{autoProgress.phase}</p></div>
              <strong className="auto-progress-count">{autoProgress.total > 0 ? Math.min(autoProgress.completed, autoProgress.total).toLocaleString("ru-RU") : 0} / {autoProgress.total.toLocaleString("ru-RU")}</strong>
            </div>
            <div className="auto-progress-track"><span style={{ width: `${autoProgress.total > 0 ? Math.min(100, autoProgress.completed / autoProgress.total * 100) : 0}%` }}/></div>
            <div className="auto-progress-metrics">
              <div><span>ПРОВЕРЕНО</span><b>{autoProgress.completed.toLocaleString("ru-RU")}</b></div>
              <div><span>ДОСТУПНО</span><b>{autoProgress.available.toLocaleString("ru-RU")}</b></div>
              <div><span>ОСТАЛОСЬ</span><b>{Math.max(0, autoProgress.total - autoProgress.completed).toLocaleString("ru-RU")}</b></div>
              <div><span>ВРЕМЯ</span><b>{(autoProgress.elapsed_ms / 1000).toFixed(1)} с</b></div>
            </div>
            {autoProgress.current_endpoint && <div className="auto-progress-line"><span>СЕЙЧАС ПРОВЕРЯЕТСЯ</span><b>{autoProgress.current_endpoint}</b></div>}
            {autoProgress.best_endpoint && <div className="auto-progress-line"><span>ТЕКУЩИЙ ЛИДЕР</span><b>{autoProgress.best_endpoint} · {autoProgress.best_latency_ms ?? "—"} мс</b></div>}
            {autoProgress.message && <div className="panel-footer"><span>{autoProgress.message}</span></div>}
          </section>}
          {autoResult?.message && !autoProgress.is_running && <div className="catalog-notice"><Activity size={15}/><span>{autoResult.message}</span></div>}
          {autoResult?.selected && <section className="panel">
            <div className="panel-heading"><div><h3>Выбранный маршрут</h3><p>Кандидат прошёл первый проход и повторные проверки</p></div><span className="state-pill on">VERIFIED</span></div>
            <div className="connection-row"><div className="connection-icon"><Network size={18}/></div><div className="connection-info"><b>{autoResult.selected.protocol} · {autoResult.selected.server}:{autoResult.selected.port}</b><span>{autoResult.verified_latency_ms ?? "—"} мс · проверено из текущей сети · следующий сеанс Telegram пойдёт через этот upstream</span></div><span className="muted"><CheckCircle2 size={16}/></span></div>
          </section>}
          <div className="content-grid">
            <section className="panel"><div className="panel-heading"><div><h3>Как работает подбор</h3><p>Отбрасываем узлы, которые не достигают Telegram DC</p></div></div>
              <div className="connection-row"><div className="connection-icon">1</div><div className="connection-info"><b>Первый проход</b><span>Проверяется каждый поддерживаемый узел из каталога; одновременно выполняется не более 24 проверок.</span></div></div>
              <div className="connection-row"><div className="connection-icon">2</div><div className="connection-info"><b>Повторная проверка</b><span>Пять самых быстрых доступных кандидатов проходят ещё две проверки каждый; нестабильные узлы исключаются.</span></div></div>
              <div className="connection-row"><div className="connection-icon">3</div><div className="connection-info"><b>Маршрут Telegram</b><span>Профиль выбирается для локального MTProto relay на 127.0.0.1:1443.</span></div></div>
              <div className="panel-footer"><span>Автоподбор не гарантирует обход, если сеть блокирует все доступные маршруты.</span></div>
            </section>
            <section className="panel quick-panel"><div className="panel-heading"><div><h3>Вручную</h3><p>Выбери прокси из каталога</p></div></div>
              <button className="quick-action" onClick={() => setSection("catalog")}><span><Network size={17}/></span><div><b>Открыть прокси-каталог</b><small>Проверить отдельные MTProto / SOCKS5</small></div><ChevronRight size={16}/></button>
            </section>
          </div>
        </> : section === "profiles" ? <>
          <div className="page-heading">
            <div><div className="eyebrow">ENCRYPTED LOCAL VAULT</div><h1>Мои прокси</h1><p>Сохранённые внешние upstream-профили. Секреты и пароли зашифрованы локально.</p></div>
            <button className="button primary" onClick={openNewProfile}><Plus size={15}/>Добавить профиль</button>
          </div>
          <div className="catalog-summary">
            <div><span>СОХРАНЕНО</span><strong>{profiles.profiles.length} / 50</strong></div>
            <div><span>ВЫБРАННЫЙ UPSTREAM</span><strong>{profiles.profiles.find(p => p.id === profiles.selected_id)?.name ?? "AUTO"}</strong></div>
            <div><span>ИЗБРАННОЕ</span><strong>{profiles.favorite_ids.length}</strong></div>
            <div><span>ХРАНИЛИЩЕ</span><strong>Windows Credential Manager</strong></div>
          </div>
          <div className="panel">
            <div className="panel-heading"><div><h3>Сохранённые профили</h3><p>Профиль проверяется из текущей сети перед выбором</p></div>
              {profiles.selected_id && <button className="proxy-action" disabled={engineBusy} onClick={() => void clearSelectedProfile()}>Сбросить upstream</button>}
            </div>
            {profilesLoading ? <div className="empty-state"><LoaderCircle size={20} className="spin"/><b>Открываю хранилище</b></div> :
              profiles.profiles.length === 0 ? <div className="empty-state"><div className="empty-icon"><Bookmark size={20}/></div><b>Пока нет сохранённых профилей</b><span>Добавь MTProto или SOCKS5 вручную либо выбери проверенный узел из каталога.</span><button className="button secondary" onClick={openNewProfile}><Plus size={14}/>Добавить профиль</button></div> :
              <div className="profile-list">{profiles.profiles.map(profile => {
                const busy = profileBusy === profile.id;
                const selected = profiles.selected_id === profile.id;
                return <article className="profile-card" key={profile.id}>
                  <div className="profile-card-main">
                    <div className="profile-card-identity">
                      <span className="proxy-type">{profile.protocol.toUpperCase()}</span>
                      <b>{profile.name}</b>
                      <small>{profile.host}:{profile.port}</small>
                    </div>
                    <span className={selected ? "state-pill on" : "state-pill"}>{selected ? "SELECTED" : "SAVED"}</span>
                  </div>
                  <div className="profile-card-actions">
                    <button className="proxy-action" disabled={busy} onClick={() => void testSavedProfile(profile)}>{busy ? <LoaderCircle size={13} className="spin"/> : <Activity size={13}/>}Проверить</button>
                    <button className="proxy-action primary" disabled={busy || selected || engineBusy} onClick={() => void selectSavedProfile(profile)}><CheckCircle2 size={13}/>{selected ? "Выбран" : "Выбрать"}</button>
                    <button className="proxy-action" disabled={busy} onClick={() => openEditProfile(profile)}><Settings size={13}/>Изменить</button>
                    <button className="proxy-action danger" disabled={busy} onClick={() => void deleteSavedProfile(profile)}><Trash2 size={13}/>Удалить</button>
                  </div>
                </article>;
              })}</div>}
            <div className="panel-footer"><span>Локальный секрет, MTProto-ключи и SOCKS5-пароли не отправляются в каталог или RNMD ID.</span></div>
          </div>
          {profileForm && <div className="modal-backdrop" role="presentation" onClick={event => { if (event.target === event.currentTarget) setProfileForm(null); }}>
            <section className="profile-modal" role="dialog" aria-modal="true" aria-label="Редактирование профиля">
              <div className="panel-heading"><div><h3>{profiles.profiles.some(p => p.id === profileForm.id) ? "Изменить профиль" : "Новый профиль"}</h3><p>Данные сохраняются в зашифрованном локальном хранилище</p></div><button className="icon-button" onClick={() => setProfileForm(null)}><X size={16}/></button></div>
              <form onSubmit={event => { event.preventDefault(); void saveProfile(); }}>
                <label className="form-field"><span>НАЗВАНИЕ</span><input required maxLength={48} value={profileForm.name} onChange={event => setProfileForm(p => p ? { ...p, name: event.target.value } : p)} placeholder="Домашний MTProto"/></label>
                <div className="form-grid">
                  <label className="form-field"><span>ПРОТОКОЛ</span><select value={profileForm.protocol} onChange={event => setProfileForm(p => p ? { ...p, protocol: event.target.value as "MTProto" | "SOCKS5" } : p)}><option value="MTProto">MTProto</option><option value="SOCKS5">SOCKS5</option></select></label>
                  <label className="form-field"><span>ПОРТ</span><input required inputMode="numeric" value={profileForm.port} onChange={event => setProfileForm(p => p ? { ...p, port: event.target.value } : p)} placeholder="443"/></label>
                </div>
                <label className="form-field"><span>СЕРВЕР / IP</span><input required value={profileForm.host} onChange={event => setProfileForm(p => p ? { ...p, host: event.target.value } : p)} placeholder="proxy.example.com"/></label>
                {profileForm.protocol === "MTProto" ?
                  <label className="form-field"><span>MTProto SECRET</span><input required type="password" autoComplete="off" value={profileForm.secret} onChange={event => setProfileForm(p => p ? { ...p, secret: event.target.value } : p)} placeholder="32 hex characters или dd + secret"/></label> :
                  <>
                    <label className="form-field"><span>USERNAME (НЕОБЯЗАТЕЛЬНО)</span><input autoComplete="off" value={profileForm.username} onChange={event => setProfileForm(p => p ? { ...p, username: event.target.value } : p)} placeholder="username"/></label>
                    <label className="form-field"><span>PASSWORD (НЕОБЯЗАТЕЛЬНО)</span><input type="password" autoComplete="new-password" value={profileForm.password} onChange={event => setProfileForm(p => p ? { ...p, password: event.target.value } : p)} placeholder="password"/></label>
                  </>}
                <div className="modal-actions"><button type="button" className="button secondary" onClick={() => setProfileForm(null)}>Отмена</button><button type="submit" className="button primary" disabled={profileBusy === profileForm.id || !profileForm.name.trim() || !profileForm.host.trim()}><Save size={14}/>Сохранить</button></div>
              </form>
            </section>
          </div>}
        </> : section === "favorites" ? <>
          <div className="page-heading"><div><div className="eyebrow">SAVED DESTINATIONS</div><h1>Избранное</h1><p>Прокси из каталога, которые ты отметил звёздочкой.</p></div><button className="button secondary" onClick={() => void loadCatalog(true)} disabled={catalogLoading}><RefreshCw size={14} className={catalogLoading ? "spin" : ""}/>Обновить</button></div>
          <div className="catalog-summary">
            <div><span>В ИЗБРАННОМ</span><strong>{profiles.favorite_ids.length}</strong></div>
            <div><span>ДОСТУПНО В КАТАЛОГЕ</span><strong>{catalog?.proxies.filter(p => profiles.favorite_ids.includes(p.id)).length ?? 0}</strong></div>
            <div><span>ВЫБРАННЫЙ UPSTREAM</span><strong>{profiles.profiles.find(p => p.id === profiles.selected_id)?.name ?? "AUTO"}</strong></div>
            <div><span>ПРОВЕРКА</span><strong>Из текущей сети</strong></div>
          </div>
          {!catalog ? <div className="empty-state"><LoaderCircle size={20} className="spin"/><b>Загружаю каталог</b><span>Избранные адреса сопоставляются с актуальным источником.</span></div> :
            catalog.proxies.filter(proxy => profiles.favorite_ids.includes(proxy.id)).length === 0 ?
              <div className="empty-state"><div className="empty-icon"><Heart size={20}/></div><b>Избранное пока пустое</b><span>Открой каталог и нажми на звёздочку рядом с нужным узлом.</span><button className="button secondary" onClick={() => setSection("catalog")}>Открыть каталог</button></div> :
              <div className="proxy-list">{catalog.proxies.filter(proxy => profiles.favorite_ids.includes(proxy.id)).map(proxy => {
                const result = testResults[proxy.id];
                const isTesting = !!testing[proxy.id];
                const isConnecting = connecting === proxy.id;
                return <article className="proxy-card" key={proxy.id}>
                  <div className="proxy-card-main"><div className="proxy-identity"><span className="proxy-type">{proxy.protocol.toUpperCase()}</span><b>{proxy.server}:{proxy.port}</b><small>{proxy.country ?? "Страна неизвестна"}</small></div><div className="proxy-meta"><div><span>RTT ИСТОЧНИКА</span><b>{proxy.source_rtt_ms ?? "—"}{proxy.source_rtt_ms != null ? " мс" : ""}</b></div><div><span>UPTIME</span><b>{proxy.uptime != null ? `${Math.round(proxy.uptime * 100)}%` : "—"}</b></div></div></div>
                  <div className="proxy-card-bottom"><div className={result ? (result.success ? "proxy-test-state success" : "proxy-test-state error") : "proxy-test-state"}>{result ? result.success ? `Telegram DC2 · ${result.elapsed_ms} мс` : result.detail : "Нужна проверка из текущей сети"}</div>
                    <div className="proxy-actions"><button className="proxy-action" onClick={() => void toggleFavorite(proxy)}><Star size={13} fill="currentColor"/>Убрать</button><button className="proxy-action" disabled={!proxy.supported || isTesting} onClick={() => void testProxy(proxy)}>{isTesting ? <LoaderCircle size={13} className="spin"/> : <Activity size={13}/>}Проверить</button><button className="proxy-action primary" disabled={!proxy.supported || !result?.success || isConnecting || engineBusy} onClick={() => void connectThroughTgu(proxy)}>{isConnecting ? <LoaderCircle size={13} className="spin"/> : <ExternalLink size={13}/>}Подключить</button></div>
                  </div>
                </article>;
              })}</div>}
        </> : section === "history" ? <>
          <div className="page-heading"><div><div className="eyebrow">LOCAL ACTIVITY LOG</div><h1>История</h1><p>Проверки узлов, выбор профиля, автоподбор и переключения upstream.</p></div><button className="button secondary" onClick={() => void loadHistory()} disabled={historyLoading}><RefreshCw size={14} className={historyLoading ? "spin" : ""}/>Обновить</button></div>
          <div className="catalog-summary">
            <div><span>ЗАПИСЕЙ</span><strong>{historyEntries.length}</strong></div>
            <div><span>УСПЕШНЫХ</span><strong>{historyEntries.filter(entry => entry.success).length}</strong></div>
            <div><span>ОШИБОК</span><strong>{historyEntries.filter(entry => !entry.success).length}</strong></div>
            <div><span>ХРАНЕНИЕ</span><strong>До 500 событий</strong></div>
          </div>
          <div className="panel">
            <div className="panel-heading"><div><h3>Последние события</h3><p>Секреты прокси и пароли в историю не записываются</p></div><button className="proxy-action danger" disabled={!historyEntries.length} onClick={() => void clearHistory()}><Trash2 size={13}/>Очистить</button></div>
            {historyLoading ? <div className="empty-state"><LoaderCircle size={20} className="spin"/><b>Загружаю историю</b></div> :
              historyEntries.length === 0 ? <div className="empty-state"><div className="empty-icon"><History size={20}/></div><b>Событий пока нет</b><span>Результаты локальных проверок и автоматических переключений появятся здесь.</span></div> :
              <div className="history-list">{historyEntries.map(entry => <article className="history-entry" key={entry.id}>
                <div className="history-entry-icon">{entry.success ? <CheckCircle2 size={16}/> : <XCircle size={16}/>}</div>
                <div className="history-entry-main"><b>{entry.action.replaceAll("_", " ") } · {entry.protocol}</b><span>{entry.endpoint}</span><small>{new Date(entry.timestamp_ms).toLocaleString("ru-RU")} · {entry.elapsed_ms} мс</small>{entry.detail && <p>{entry.detail}</p>}</div>
                <span className={entry.success ? "state-pill on" : "state-pill"}>{entry.success ? "OK" : "FAIL"}</span>
              </article>)}</div>}
          </div>
        </> : section === "settings" ? <>
          <div className="page-heading"><div><div className="eyebrow">MONITORING & ROUTING</div><h1>Настройки</h1><p>Параметры автосмены и транспортов, синхронизированные с мобильной моделью.</p></div><button className="button primary" disabled={!desktopSettings || settingsSaving} onClick={() => void saveDesktopSettings()}>{settingsSaving ? <LoaderCircle size={14} className="spin"/> : <Save size={14}/>}Сохранить</button></div>
          {settingsLoading || !desktopSettings ? <div className="empty-state"><LoaderCircle size={20} className="spin"/><b>Загружаю настройки</b></div> : <>
            <section className="panel settings-panel">
              <div className="panel-heading"><div><h3>Автоматическое переключение</h3><p>Контролирует выбранный внешний MTProto / SOCKS5 upstream</p></div><span className={desktopSettings.auto_failover_enabled ? "state-pill on" : "state-pill"}>{desktopSettings.auto_failover_enabled ? "ENABLED" : "DISABLED"}</span></div>
              <label className="settings-toggle"><span><b>Включить авто-переключение</b><small>Проверять активный upstream и искать замену при серии ошибок</small></span><input type="checkbox" checked={desktopSettings.auto_failover_enabled} onChange={event => setDesktopSettings({ ...desktopSettings, auto_failover_enabled: event.target.checked })}/></label>
              <label className="form-field"><span>ИНТЕРВАЛ ПРОВЕРКИ · СЕКУНДЫ (30–180)</span><input type="number" min={30} max={180} step={5} value={desktopSettings.check_interval_seconds} onChange={event => setDesktopSettings({ ...desktopSettings, check_interval_seconds: Number(event.target.value) })}/></label>
              <label className="form-field"><span>ПОРОГ ОШИБОК ДО FAILOVER (2–5)</span><input type="number" min={2} max={5} value={desktopSettings.failure_threshold} onChange={event => setDesktopSettings({ ...desktopSettings, failure_threshold: Number(event.target.value) })}/></label>
              <label className="form-field"><span>ОБНОВЛЕНИЕ КАТАЛОГА · МИНУТЫ (2–30)</span><input type="number" min={2} max={30} value={desktopSettings.catalog_refresh_minutes} onChange={event => setDesktopSettings({ ...desktopSettings, catalog_refresh_minutes: Number(event.target.value) })}/></label>
              <label className="form-field"><span>COOLDOWN ОТКАЗАВШЕГО ПРОКСИ · СЕКУНДЫ</span><input type="number" min={120} max={3600} step={60} value={desktopSettings.failed_profile_cooldown_seconds} onChange={event => setDesktopSettings({ ...desktopSettings, failed_profile_cooldown_seconds: Number(event.target.value) })}/></label>
              <label className="form-field"><span>ПАУЗА МЕЖДУ ПОПЫТКАМИ SWITCH · СЕКУНДЫ</span><input type="number" min={30} max={600} step={30} value={desktopSettings.switch_cooldown_seconds} onChange={event => setDesktopSettings({ ...desktopSettings, switch_cooldown_seconds: Number(event.target.value) })}/></label>
              <div className="panel-footer"><span>Активный upstream: {status.selected_upstream ?? "AUTO transport fallback"}</span><span>{status.failure_streak} / {desktopSettings.failure_threshold} ошибок · переключений: {status.switch_count}</span></div>
            </section>
            <section className="panel settings-panel">
              <div className="panel-heading"><div><h3>Транспорты</h3><p>Настройки прямого Telegram WebSocket и резервного Cloudflare маршрута</p></div></div>
              <label className="settings-toggle"><span><b>Прямой Telegram WebSocket первым</b><small>Сначала проверять поддерживаемые redirect DC маршруты</small></span><input type="checkbox" checked={desktopSettings.direct_websocket_first} onChange={event => setDesktopSettings({ ...desktopSettings, direct_websocket_first: event.target.checked })}/></label>
              <label className="settings-toggle"><span><b>Cloudflare WebSocket fallback</b><small>Переходить к резервному доменному пулу, если прямой маршрут недоступен</small></span><input type="checkbox" checked={desktopSettings.cloudflare_websocket_enabled} onChange={event => setDesktopSettings({ ...desktopSettings, cloudflare_websocket_enabled: event.target.checked })}/></label>
              <label className="settings-toggle"><span><b>Автооптимизация соединения</b><small>Разрешить обновление автоматически управляемого профиля при значимом улучшении</small></span><input type="checkbox" checked={desktopSettings.auto_optimize_connection} onChange={event => setDesktopSettings({ ...desktopSettings, auto_optimize_connection: event.target.checked })}/></label>
              <label className="form-field"><span>WEBSOCKET PING · СЕКУНДЫ</span><input type="number" min={30} max={600} step={15} value={desktopSettings.websocket_ping_interval_seconds} onChange={event => setDesktopSettings({ ...desktopSettings, websocket_ping_interval_seconds: Number(event.target.value) })}/></label>
            </section>
            <section className="panel settings-panel">
              <div className="panel-heading"><div><h3>TGU Community Network</h3><p>Анонимная статистика качества публичных узлов</p></div></div>
              <label className="settings-toggle"><span><b>Автоматически отправлять результаты сканирования</b><small>Включено по умолчанию. После автоподбора передаются ID прокси, результат и RTT; IP, аккаунт Telegram и содержимое трафика не отправляются. Можно отключить здесь.</small></span><input type="checkbox" checked={desktopSettings.community_feedback_enabled} onChange={event => setDesktopSettings({ ...desktopSettings, community_feedback_enabled: event.target.checked })}/></label>
            </section>
            <section className="panel settings-panel">
              <div className="panel-heading"><div><h3>Состояние мониторинга</h3><p>Текущие данные движка</p></div></div>
              <div className="catalog-summary">
                <div><span>UPTIME</span><strong>{formatDuration(status.uptime_seconds)}</strong></div>
                <div><span>ПОСЛЕДНЯЯ ПРОВЕРКА</span><strong>{status.last_check_epoch_ms ? new Date(status.last_check_epoch_ms).toLocaleTimeString("ru-RU") : "—"}</strong></div>
                <div><span>ПОСЛЕДНИЙ RTT</span><strong>{status.last_check_latency_ms != null ? `${status.last_check_latency_ms} мс` : "—"}</strong></div>
                <div><span>SWITCHES</span><strong>{status.switch_count}</strong></div>
              </div>
            </section>
          </>}
        </> : section === "diagnostics" ? <>
          <div className="page-heading"><div><div className="eyebrow">NETWORK & TELEGRAM DC</div><h1>Диагностика</h1><p>Проверка HTTPS/TLS доменов Telegram, Cloudflare и TCP-портов DC1–DC5.</p></div><button className="button primary" disabled={diagnosticsRunning} onClick={() => void runDiagnostics()}>{diagnosticsRunning ? <LoaderCircle size={14} className="spin"/> : <Activity size={14}/>}Запустить диагностику</button></div>
          <section className="panel">
            <div className="panel-heading">
              <div><h3>TGU DPI · технический PoC</h3><p>Проверка WinDivert и окружения. Этот тест не запускает фильтрацию и не меняет сетевые настройки.</p></div>
              <button className="button secondary" disabled={dpiProbeLoading} onClick={() => void runDpiProbe()}>{dpiProbeLoading ? <LoaderCircle size={14} className="spin"/> : <ShieldCheck size={14}/>}Проверить DPI</button>
            </div>
            {dpiProbe ? <>
              <div className="catalog-summary">
                <div><span>ПЛАТФОРМА</span><strong>{dpiProbe.platform}</strong></div>
                <div><span>СТАТУС</span><strong>{dpiProbe.status}</strong></div>
                <div><span>ADMIN</span><strong>{dpiProbe.elevated ? "Да" : "Нет"}</strong></div>
                <div><span>ПЕРЕХВАТ</span><strong>{dpiProbe.runtime_started ? "Активен" : "Не запускался"}</strong></div>
              </div>
              <div className="diagnostic-row"><span className={dpiProbe.winws2_found ? "diagnostic-dot success" : "diagnostic-dot"}/><div><b>winws2.exe</b><small>{dpiProbe.winws2_found ? "Найден" : "Не найден"}</small></div></div>
              <div className="diagnostic-row"><span className={dpiProbe.windivert_dll_found ? "diagnostic-dot success" : "diagnostic-dot"}/><div><b>WinDivert.dll</b><small>{dpiProbe.windivert_dll_found ? "Найдена" : "Не найдена"}</small></div></div>
              <div className="diagnostic-row"><span className={dpiProbe.windivert_driver_found ? "diagnostic-dot success" : "diagnostic-dot"}/><div><b>WinDivert64.sys</b><small>{dpiProbe.windivert_driver_found ? "Найден" : "Не найден"}</small></div></div>
              <div className="diagnostic-row"><span className={dpiProbe.cygwin_runtime_found ? "diagnostic-dot success" : "diagnostic-dot"}/><div><b>cygwin1.dll</b><small>{dpiProbe.cygwin_runtime_found ? "Найдена" : "Не найдена"}</small></div></div>
              <div className="panel-footer"><span>{dpiProbe.message} Папка проверки: {dpiProbe.resource_directory || "—"}</span></div>
            </> : <div className="empty-state"><span>Проверка ещё не запускалась.</span></div>}
          </section>
          {diagnostics && <>
            <div className="catalog-summary">
              <div><span>TELEGRAM / HTTPS</span><strong>{diagnostics.network_successes} / {diagnostics.network_total}</strong></div>
              <div><span>TELEGRAM DC TCP</span><strong>{diagnostics.dc_successes} / {diagnostics.dc_total}</strong></div>
              <div><span>СОЕДИНЕНИЕ</span><strong>{status.engine_ready ? "TGU running" : "TGU stopped"}</strong></div>
              <div><span>ЗАПУСК ТЕСТА</span><strong>{new Date(diagnostics.generated_at_ms).toLocaleTimeString("ru-RU")}</strong></div>
            </div>
            <section className="panel">
              <div className="panel-heading"><div><h3>Сеть и TLS</h3><p>TCP/443, TLS handshake и HTTP-ответ</p></div></div>
              {diagnostics.network_checks.map(check => <div className="diagnostic-row" key={check.host}><span className={check.tls_http ? "diagnostic-dot success" : check.tcp_443 ? "diagnostic-dot warning" : "diagnostic-dot"}/><div><b>{check.host}</b><small>{check.detail}</small></div><strong>{check.elapsed_ms} мс</strong></div>)}
            </section>
            <section className="panel">
              <div className="panel-heading"><div><h3>Telegram DC и access points</h3><p>TCP-подключение к reference IPv4/IPv6 адресам на 80 и 443</p></div></div>
              {diagnostics.dc_checks.map((check, index) => <div className="diagnostic-row" key={`${check.address}-${check.port}-${index}`}><span className={check.reachable ? "diagnostic-dot success" : "diagnostic-dot"}/><div><b>{check.dc_label} · {check.address}:{check.port}</b><small>{check.address_family} · {check.source} · {check.detail}</small></div><strong>{check.elapsed_ms} мс</strong></div>)}
              <div className="panel-footer"><span>Список адресов — диагностический reference snapshot; актуальный список DC определяется Telegram.</span></div>
            </section>
          </>}
          {!diagnostics && <div className="empty-state"><div className="empty-icon"><Activity size={20}/></div><b>Диагностика ещё не запускалась</b><span>Проверка покажет, какие домены и DC доступны из текущей сети. Она не меняет системные сетевые настройки.</span><button className="button secondary" disabled={diagnosticsRunning} onClick={() => void runDiagnostics()}>Запустить диагностику</button></div>}
        </> : section === "account" ? <>
          <div className="page-heading"><div><div className="eyebrow">RNMDTEAM / IDENTITY</div><h1>RNMD ID</h1><p>Единая учётная запись TGU для Android и Windows. Вход через Telegram.</p></div>
            <button type="button" className="icon-button" title="Обновить профиль" disabled={rnmdBusy || rnmdLoading} onClick={() => void reloadRnmd()}><RefreshCw size={17} className={rnmdLoading ? "spin" : ""}/></button></div>
          <section className="rnmd-panel">
            {rnmdLoading ? <div className="rnmd-loading"><LoaderCircle size={20} className="spin"/>Загружаю RNMD ID…</div> :
              rnmdAccount ? <>
                <div className="rnmd-profile">
                  <div className="rnmd-avatar">{rnmdAccount.avatar_url ? <img src={rnmdAccount.avatar_url} alt="" referrerPolicy="no-referrer"/> : <UserRound size={30}/>}</div>
                  <div className="rnmd-identity"><span className="eyebrow">ПРОФИЛЬ RNMD ID</span><h2>{rnmdAccount.display_name}</h2><p>{rnmdAccount.telegram_username ? `@${rnmdAccount.telegram_username}` : "Telegram · аккаунт подключён"}</p></div>
                  <span className="state-pill on">{rnmdAccount.has_tgu_plus ? "TGU+" : "TGU FREE"}</span>
                </div>
                <div className="rnmd-info-grid">
                  <div><span>УЧЁТНАЯ ЗАПИСЬ</span><strong>{rnmdAccount.user_id}</strong></div>
                  <div><span>УСТРОЙСТВА</span><strong>{rnmdAccount.devices.length}</strong></div>
                  <div><span>ПОКУПКИ</span><strong>{rnmdAccount.orders.length}</strong></div>
                </div>
                <div className="rnmd-heading"><h3>Подключённые устройства</h3><span>RNMD ID</span></div>
                {rnmdAccount.devices.length ? <div className="rnmd-list">{rnmdAccount.devices.map(d => <div className="rnmd-list-row" key={d.id}><Server size={17}/><div><b>{d.device_name}</b><small>{d.platform} · {d.last_seen_at ? new Date(d.last_seen_at).toLocaleString("ru-RU") : "Нет активности"}</small></div></div>)}</div> : <p className="rnmd-muted">Устройства пока не зарегистрированы.</p>}
                <div className="rnmd-heading"><h3>История покупок</h3><span>{rnmdAccount.orders.length} записей</span></div>
                {rnmdAccount.orders.length ? <div className="rnmd-list">{rnmdAccount.orders.map(o => <div className="rnmd-list-row" key={o.id}><Bookmark size={17}/><div><b>{o.product_code}</b><small>{o.status} · {(o.amount_minor / 100).toLocaleString("ru-RU")} {o.currency}</small></div></div>)}</div> : <p className="rnmd-muted">Покупок пока нет.</p>}
                <div className="rnmd-actions"><button className="button secondary" type="button" disabled={rnmdBusy} onClick={() => void logoutRnmd()}><LogOut size={16}/>{rnmdBusy ? "Выход…" : "Выйти из RNMD ID"}</button></div>
              </> :
              <div className="rnmd-guest">
                <div className="rnmd-guest-icon"><UserRound size={29}/></div><div className="eyebrow">ЕДИНЫЙ АККАУНТ RNMDTEAM</div>
                <h2>Один аккаунт. Все устройства.</h2>
                <p>Войди через Telegram, чтобы открыть профиль RNMD ID, список устройств и покупки. Использовать TGU можно и без входа.</p>
                <button type="button" className="button primary" disabled={rnmdBusy} onClick={() => void loginRnmd()}>{rnmdBusy ? <LoaderCircle className="spin" size={16}/> : <UserRound size={16}/>} {rnmdBusy ? "Ожидаю вход через Telegram…" : "Продолжить с Telegram"}</button>
                <span className="rnmd-muted">Авторизация откроется в системном браузере. Понадобится подтверждение Telegram.</span>
              </div>}
            {rnmdError && <div className="rnmd-error" role="alert">{rnmdError}</div>}
          </section>
        </> : <section className="placeholder-page">
          <div className="eyebrow">TGU DESKTOP</div><h1>{title}</h1>
          <p>TGU Desktop — настольный клиент экосистемы TGU.</p>
          <div className="empty-state"><div className="empty-icon"><CircleHelp size={22}/></div><b>TGU Desktop v{appVersion}</b><span>Локальный MTProto relay, каталог прокси, шифрованные профили и диагностика Telegram DC.</span></div>
        </section>}
      </div>
    </main>
  </div></div>;
}

export default App;
