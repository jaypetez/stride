import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Disclaimer, isStopFlag, SafetyFlags } from './SafetyFlags';

const STOP =
  'Symptoms you described (e.g. chest pain, dizziness, fainting) can be serious. ' +
  'Stop exercising and seek advice from a medical professional before continuing.';
const WARN =
  'You mentioned pain, injury, or illness — prioritize recovery and do not push through it.';

describe('isStopFlag', () => {
  it('matches the STOP wording from the core keyword rules only', () => {
    expect(isStopFlag(STOP)).toBe(true);
    expect(isStopFlag(WARN)).toBe(false);
  });
});

describe('SafetyFlags', () => {
  it('renders nothing when there are no flags', () => {
    const { container } = render(<SafetyFlags flags={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders STOP flags first, then warnings, each as an alert', () => {
    render(<SafetyFlags flags={[WARN, STOP]} />);
    const alerts = screen.getAllByRole('alert');
    expect(alerts).toHaveLength(2);
    expect(alerts[0]).toHaveClass('flag-stop');
    expect(alerts[0]).toHaveTextContent(/^STOP/);
    expect(alerts[1]).toHaveClass('flag-warn');
    expect(alerts[1]).toHaveTextContent(/^Warning/);
  });
});

describe('Disclaimer', () => {
  it('renders the disclaimer text', () => {
    render(<Disclaimer text="Not medical advice." />);
    expect(screen.getByText('Not medical advice.')).toBeInTheDocument();
  });

  it('renders nothing without text', () => {
    const { container } = render(<Disclaimer />);
    expect(container).toBeEmptyDOMElement();
  });
});
