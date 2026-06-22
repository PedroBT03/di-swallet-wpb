export type Uc1Step = "passkey" | "init" | "hsm" | "ready";

interface Uc1StepperProps {
  passkeyDone: boolean;
  walletInitialized: boolean;
  hsmReady: boolean;
}

const STEPS: { id: Uc1Step; label: string }[] = [
  { id: "passkey", label: "Register passkey" },
  { id: "init", label: "Initialize wallet" },
  { id: "hsm", label: "HSM key" },
  { id: "ready", label: "Ready to issue" },
];

function currentStep(props: Uc1StepperProps): Uc1Step {
  if (props.hsmReady && props.walletInitialized) {
    return "ready";
  }
  if (props.walletInitialized) {
    return "hsm";
  }
  if (props.passkeyDone) {
    return "init";
  }
  return "passkey";
}

export function Uc1Stepper(props: Uc1StepperProps) {
  const current = currentStep(props);
  const currentIndex = STEPS.findIndex((step) => step.id === current);

  return (
    <ol className="present-stepper issue-stepper" aria-label="Wallet bootstrap">
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
