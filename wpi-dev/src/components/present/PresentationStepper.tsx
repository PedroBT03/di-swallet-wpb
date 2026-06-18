import type { PresentationState } from "../../types/openid4vp";
import { presentStepForState } from "../../features/present/state";

interface PresentationStepperProps {
  state: PresentationState | null;
}

const STEPS = [
  { id: "authorize" as const, label: "Authorize" },
  { id: "consent" as const, label: "Consent" },
  { id: "outcome" as const, label: "Outcome" },
];

export function PresentationStepper({ state }: PresentationStepperProps) {
  const current = presentStepForState(state);
  const currentIndex = STEPS.findIndex((s) => s.id === current);

  return (
    <ol className="present-stepper" aria-label="Presentation flow">
      {STEPS.map((step, index) => {
        const done = index < currentIndex;
        const active = step.id === current;
        const className = [
          "present-stepper__step",
          done ? "present-stepper__step--done" : "",
          active ? "present-stepper__step--active" : "",
        ]
          .filter(Boolean)
          .join(" ");

        return (
          <li key={step.id} className={className} aria-current={active ? "step" : undefined}>
            <span className="present-stepper__index">{index + 1}</span>
            <span className="present-stepper__label">{step.label}</span>
          </li>
        );
      })}
    </ol>
  );
}
