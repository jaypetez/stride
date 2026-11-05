import { describe, expect, it } from 'vitest';
import {
  classifierWarnings,
  detectRedFlags,
  INCOMPLETE_SCREENING_FLAG,
  PARQ_QUESTIONS,
  ScreeningAnswers,
  screenReadiness,
  shouldHalt,
} from '../src/coach/safety';

const stopFor = (text: string) => shouldHalt(detectRedFlags({ text }));

describe('detectRedFlags — STOP keyword table (AUD-005)', () => {
  // Every phrasing here must halt coaching and refer to a professional.
  const POSITIVE: string[] = [
    'Felt some chest pain on the last hill',
    'CHEST TIGHTNESS after intervals',
    'my chest feels tight on climbs',
    'chest felt heavy during the tempo',
    'a tightness in my chest at the end',
    'I was short of breath at an easy pace',
    'shortness of breath walking upstairs',
    "I can't breathe properly when I push",
    'I can’t breathe on the hills', // curly apostrophe
    'couldnt catch my breath for minutes',
    'could not breathe after the sprint',
    'trouble breathing at night',
    'struggling to breathe on easy runs',
    'got dizzy near the end',
    'some dizziness after the long run',
    'felt lightheaded after the hill',
    'a bit light-headed at the finish',
    'light headedness when I stood up',
    'I nearly fainted at the finish',
    'passed out briefly after the race',
    'blacked out for a second',
    'heart palpitations during the tempo',
    'Palpitations on the last rep',
    'had a palpitation at rest',
    'heart was racing at rest',
    'my heart kept skipping on the cooldown',
    'irregular heartbeat this morning',
    'severe pain in my knee',
  ];

  // Ordinary training talk that must NOT halt coaching.
  const NEGATIVE: string[] = [
    'Felt great, smooth and relaxed!',
    'tight hamstrings after the long run',
    'Wore my new chest strap',
    'heart rate stayed in zone 2',
    'breathing felt easy the whole way',
    'painting the fence after the run',
    'heavy legs on the hills',
    'light rain and a headwind',
    'race pace felt comfortable',
    'great breathwork session after yoga',
    'my heart rate monitor died mid-run',
    'chest-high hurdles drill',
  ];

  it.each(POSITIVE)('STOP: %s', (text) => {
    expect(stopFor(text)).toBe(true);
  });

  it.each(NEGATIVE)('no STOP: %s', (text) => {
    expect(stopFor(text)).toBe(false);
  });

  it('has at least 20 positive and 10 negative phrasings', () => {
    expect(POSITIVE.length).toBeGreaterThanOrEqual(20);
    expect(NEGATIVE.length).toBeGreaterThanOrEqual(10);
  });

  it('the audit misses ("palpitations", "lightheaded", "chest feels tight", "can\'t breathe") all STOP', () => {
    for (const t of ['palpitations', 'lightheaded', 'chest feels tight', "can't breathe"]) {
      expect(stopFor(t)).toBe(true);
    }
  });

  it('emits exactly one STOP flag and skips keyword warnings when halting', () => {
    const flags = detectRedFlags({ text: 'chest pain and sore legs' });
    expect(flags.filter((f) => f.severity === 'stop')).toHaveLength(1);
    expect(flags.some((f) => f.source === 'keyword' && f.severity === 'warning')).toBe(false);
  });

  it('still raises a warning (not a stop) for plain pain/soreness', () => {
    const flags = detectRedFlags({ text: 'some soreness in my calf' });
    expect(shouldHalt(flags)).toBe(false);
    expect(flags.some((f) => f.severity === 'warning' && f.source === 'keyword')).toBe(true);
  });
});

describe('screenReadiness (AUD-001)', () => {
  const allNo = PARQ_QUESTIONS.map(() => false);

  it('clears a complete all-no answer list', () => {
    expect(screenReadiness(allNo)).toEqual({
      cleared: true,
      flaggedQuestions: [],
      healthFlags: [],
    });
  });

  it('flags each "yes" with the question text and a short label', () => {
    const answers = [...allNo];
    answers[1] = true;
    answers[5] = true;
    const r = screenReadiness(answers);
    expect(r.cleared).toBe(false);
    expect(r.flaggedQuestions).toEqual([PARQ_QUESTIONS[1], PARQ_QUESTIONS[5]]);
    expect(r.healthFlags).toEqual(['chest pain on exertion', 'blood-pressure/heart medication']);
  });

  it.each([
    ['empty', []],
    ['short', [false, false]],
    ['one short', allNo.slice(1)],
    ['long', [...allNo, false]],
  ])('never clears an incomplete (%s) answer list', (_name, answers) => {
    const r = screenReadiness(answers);
    expect(r.cleared).toBe(false);
    expect(r.healthFlags).toContain(INCOMPLETE_SCREENING_FLAG);
  });

  it('ScreeningAnswers accepts exactly one answer per question', () => {
    expect(ScreeningAnswers.safeParse(allNo).success).toBe(true);
    expect(ScreeningAnswers.safeParse([]).success).toBe(false);
    expect(ScreeningAnswers.safeParse([...allNo, true]).success).toBe(false);
    expect(ScreeningAnswers.safeParse(allNo.map(() => 'no')).success).toBe(false);
  });
});

describe('classifierWarnings', () => {
  it('maps known labels to WARNING flags from the classifier source', () => {
    const flags = classifierWarnings(['injury', 'sleep']);
    expect(flags).toHaveLength(2);
    for (const f of flags) {
      expect(f.severity).toBe('warning');
      expect(f.source).toBe('classifier');
    }
    expect(flags[0].message).toMatch(/injury/);
    expect(flags[1].message).toMatch(/sleep/);
  });

  it('normalizes case/whitespace, de-duplicates, and ignores unknown labels', () => {
    const flags = classifierWarnings([' Injury ', 'INJURY', 'banana', 'stress']);
    expect(flags.map((f) => f.message.match(/injury|stress/)?.[0])).toEqual(['injury', 'stress']);
  });

  it('never emits a STOP (the keyword rules stay authoritative)', () => {
    const all = classifierWarnings([
      'injury',
      'illness',
      'pain',
      'overreaching',
      'fatigue',
      'sleep',
      'stress',
    ]);
    expect(all).toHaveLength(7);
    expect(shouldHalt(all)).toBe(false);
  });

  it('returns nothing for no labels', () => {
    expect(classifierWarnings([])).toEqual([]);
  });
});
