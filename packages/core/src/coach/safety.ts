import type { AthleteProfile, RedFlag } from '@stride/schemas';

/** Standard, non-negotiable disclaimer attached to all coaching output. */
export const DISCLAIMER =
  'Stride is for informational and educational purposes only and is not a substitute ' +
  'for professional medical advice. Consult a qualified healthcare provider before ' +
  'beginning any fitness program, especially if you have any pre-existing conditions.';

/** PAR-Q-style screening questions surfaced at onboarding. */
export const PARQ_QUESTIONS: string[] = [
  'Has a doctor ever said you have a heart condition and should only do physical activity recommended by a doctor?',
  'Do you feel pain in your chest when you do physical activity?',
  'In the past month, have you had chest pain when you were not doing physical activity?',
  'Do you lose your balance because of dizziness, or do you ever lose consciousness?',
  'Do you have a bone or joint problem that could be made worse by a change in your physical activity?',
  'Is your doctor currently prescribing drugs for your blood pressure or a heart condition?',
  'Do you know of any other reason you should not do physical activity?',
];

/** Keyword patterns that should immediately halt coaching and refer to a professional. */
const STOP_KEYWORDS: RegExp[] = [
  /\bchest pain\b/i,
  /\bchest tightness\b/i,
  /\bshort(ness)? of breath\b/i,
  /\bdizz(y|iness)\b/i,
  /\bfaint(ed|ing)?\b/i,
  /\bpass(ed)? out\b/i,
  /\bblack(ed)? out\b/i,
  /\bheart palpitation/i,
  /\bsevere pain\b/i,
];

const WARN_KEYWORDS: RegExp[] = [
  /\binjur(y|ed)\b/i,
  /\bpain\b/i,
  /\bsore(ness)?\b/i,
  /\bsick\b/i,
  /\bfever\b/i,
];

export interface RedFlagInput {
  /** Free text (e.g. an athlete note or chat message). */
  text?: string;
  profile?: AthleteProfile;
  /** Latest TSB (form). */
  tsb?: number;
  /** Latest ACWR flag. */
  acwrFlag?: 'low' | 'ok' | 'high' | 'very_high';
}

/**
 * Deterministic red-flag detection. Runs in code before/around any LLM call so
 * safety logic is explicit, not left to the model.
 */
export function detectRedFlags(input: RedFlagInput): RedFlag[] {
  const flags: RedFlag[] = [];
  const text = input.text ?? '';

  for (const re of STOP_KEYWORDS) {
    if (re.test(text)) {
      flags.push({
        severity: 'stop',
        source: 'keyword',
        message:
          'Symptoms you described (e.g. chest pain, dizziness, fainting) can be serious. ' +
          'Stop exercising and seek advice from a medical professional before continuing.',
      });
      break;
    }
  }
  if (!flags.some((f) => f.severity === 'stop')) {
    for (const re of WARN_KEYWORDS) {
      if (re.test(text)) {
        flags.push({
          severity: 'warning',
          source: 'keyword',
          message:
            'You mentioned pain, injury, or illness — prioritize recovery and do not push through it.',
        });
        break;
      }
    }
  }

  if (input.profile?.healthFlags && input.profile.healthFlags.length > 0) {
    flags.push({
      severity: 'warning',
      source: 'health',
      message: `Health screening flags on file: ${input.profile.healthFlags.join(', ')}. Get medical clearance before hard training.`,
    });
  }
  if (input.profile && input.profile.medicalClearance === false) {
    flags.push({
      severity: 'info',
      source: 'health',
      message:
        'No medical clearance recorded. Complete the readiness screening before starting a plan.',
    });
  }

  if (input.acwrFlag === 'very_high') {
    flags.push({
      severity: 'warning',
      source: 'load',
      message:
        'Your acute:chronic workload ratio is very high (>1.5) — a spike associated with elevated injury risk. Ease off.',
    });
  }
  if (typeof input.tsb === 'number' && input.tsb < -30) {
    flags.push({
      severity: 'warning',
      source: 'load',
      message:
        'Your form (TSB) is deeply negative — a sign of accumulated fatigue. Consider a recovery week.',
    });
  }

  return flags;
}

/** True if any flag requires halting coaching. */
export function shouldHalt(flags: RedFlag[]): boolean {
  return flags.some((f) => f.severity === 'stop');
}

/** Short, storable labels aligned 1:1 with `PARQ_QUESTIONS`. */
const PARQ_FLAG_LABELS: string[] = [
  'heart condition',
  'chest pain on exertion',
  'chest pain at rest',
  'dizziness or fainting',
  'bone or joint problem',
  'blood-pressure/heart medication',
  'other medical reason',
];

export interface ReadinessScreening {
  /** True only when every PAR-Q answer is "no" (safe to clear). */
  cleared: boolean;
  /** The full text of each question answered "yes". */
  flaggedQuestions: string[];
  /** Short labels suitable for `AthleteProfile.healthFlags`. */
  healthFlags: string[];
}

/**
 * PAR-Q-style readiness screening (GOAL §8). `answers[i]` is the athlete's
 * yes/no answer to `PARQ_QUESTIONS[i]`; a `true` ("yes") to any question means
 * they are not auto-cleared and should get medical sign-off first. Surfaces
 * (CLI/API/web onboarding) call this and persist the result onto the profile:
 * `medicalClearance = cleared`, `healthFlags = healthFlags`. Those fields then
 * flow into `detectRedFlags`, so the screening constrains every later request.
 */
export function screenReadiness(answers: boolean[]): ReadinessScreening {
  const flaggedQuestions: string[] = [];
  const healthFlags: string[] = [];
  for (let i = 0; i < PARQ_QUESTIONS.length; i++) {
    if (answers[i]) {
      flaggedQuestions.push(PARQ_QUESTIONS[i]);
      healthFlags.push(PARQ_FLAG_LABELS[i] ?? PARQ_QUESTIONS[i]);
    }
  }
  return { cleared: flaggedQuestions.length === 0, flaggedQuestions, healthFlags };
}

/**
 * Map Haiku-classifier concern labels to WARNING-level red flags. This is the
 * optional second pass over free text: it only ever adds warnings, so the
 * authoritative keyword STOP rules (and thus safety itself) never depend on
 * model availability. Unknown labels are ignored.
 */
export function classifierWarnings(labels: string[]): RedFlag[] {
  const messages: Record<string, string> = {
    injury:
      'Your note suggests a possible injury — prioritize recovery and consider a professional assessment before hard training.',
    illness:
      'Your note suggests illness — rest and let symptoms fully resolve before resuming intensity.',
    pain: 'Your note mentions pain — do not push through it; ease back and reassess.',
    overreaching:
      'Your note suggests you may be overreaching — an easier stretch now protects the gains you have made.',
    fatigue:
      'Your note suggests unusual fatigue — extra easy volume or a rest day may be warranted.',
    sleep:
      'Your note suggests poor sleep — recovery is compromised, so keep intensity conservative.',
    stress:
      'Your note suggests elevated life stress — treat it as training load and keep sessions easy.',
  };
  const seen = new Set<string>();
  const flags: RedFlag[] = [];
  for (const raw of labels) {
    const label = raw.trim().toLowerCase();
    const message = messages[label];
    if (message && !seen.has(label)) {
      seen.add(label);
      flags.push({ severity: 'warning', source: 'classifier', message });
    }
  }
  return flags;
}
