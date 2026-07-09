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
