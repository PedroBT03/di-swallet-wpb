import { Navigate, Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { HealthPage } from "./pages/HealthPage";
import { OnboardingPage } from "./pages/OnboardingPage";
import { LoginPage } from "./pages/LoginPage";
import { IssuePage } from "./pages/IssuePage";
import { LogPage } from "./pages/LogPage";
import { OpsPage } from "./pages/OpsPage";
import { PresentPage } from "./pages/PresentPage";
import { PrivacyPage } from "./pages/PrivacyPage";
import { PseudonymsPage } from "./pages/PseudonymsPage";
import { WalletPage } from "./pages/WalletPage";
import { SettingsPage } from "./pages/SettingsPage";

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<HealthPage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="onboarding" element={<OnboardingPage />} />
        <Route path="wallet" element={<WalletPage />} />
        <Route path="present" element={<PresentPage />} />
        <Route path="issue" element={<IssuePage />} />
        <Route path="log" element={<LogPage />} />
        <Route path="privacy" element={<PrivacyPage />} />
        <Route path="ops" element={<OpsPage />} />
        <Route path="pseudonyms" element={<PseudonymsPage />} />
        <Route path="settings" element={<SettingsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
