import type {
  Activity,
  ActivityMetrics,
  AcwrPoint,
  AnalysisResult,
  AthleteProfile,
  PlanValidation,
  PmcPoint,
  TrainingPlan,
  WorkoutSuggestion,
  ZoneDistribution,
} from '@stride/schemas';

const BASE = import.meta.env.VITE_API_BASE ?? '/api';

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`);
  if (!res.ok) throw new Error(`GET ${path} failed (${res.status})`);
  return (await res.json()) as T;
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`POST ${path} failed (${res.status})`);
  return (await res.json()) as T;
}

export interface PmcResponse {
  pmc: PmcPoint[];
  acwr: AcwrPoint[];
  latest: PmcPoint | null;
  latestAcwr: AcwrPoint | null;
  rampRatePerWeek: number;
}

export interface AnalyzeResponse {
  metrics: ActivityMetrics;
  analysis: AnalysisResult;
}

export interface NextResponse {
  context: {
    fitness?: PmcPoint;
    acwr?: AcwrPoint;
    weeklyDistribution?: ZoneDistribution;
    weeklyVolumeKm?: number;
  };
  workout: WorkoutSuggestion;
}

export interface PlanResponse {
  plan: TrainingPlan;
  validation: PlanValidation;
}

const q = (demo: boolean) => (demo ? '?demo=true' : '');

export const api = {
  health: () => getJson<{ status: string; version: string }>('/health'),
  profile: () => getJson<AthleteProfile>('/profile'),
  pmc: (demo: boolean) => getJson<PmcResponse>(`/pmc${q(demo)}`),
  activities: (demo: boolean) => getJson<Activity[]>(`/activities${q(demo)}`),
  analyze: (id: string) => getJson<AnalyzeResponse>(`/analyze/${id}`),
  next: (demo: boolean) => getJson<NextResponse>(`/next${q(demo)}`),
  plan: (body: { race?: string; weeks?: number; start?: string; date?: string; demo?: boolean }) =>
    postJson<PlanResponse>('/plan', body),
};
