import type { IssuanceState } from "../../types/openid4vci";
import { issuanceStepForState, type IssuanceStep } from "../../features/issue/state";

interface IssuanceStepperProps {
  state: IssuanceState | null;
}

const STEPS: { id: IssuanceStep; label: string }[] = [
  { id: "offer", label: "Resolve offer" },
  { id: "authorize", label: "Authorize" },
  { id: "credential", label: "Request credential" },
  { id: "consent", label: "Storage consent" },
  { id: "outcome", label: "Outcome" },
];

export function IssuanceStepper({ state }: IssuanceStepperProps) {
  const current = issuanceStepForState(state);
  const currentIndex = STEPS.findIndex((s) => s.id === current);

  return (
    <ol className="present-stepper issue-stepper" aria-label="Issuance flow">
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
