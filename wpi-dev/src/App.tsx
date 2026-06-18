import { Navigate, Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { HealthPage } from "./pages/HealthPage";
import { OnboardingPage } from "./pages/OnboardingPage";
import { LoginPage } from "./pages/LoginPage";
import { PlaceholderPage } from "./pages/PlaceholderPage";
import { PresentPage } from "./pages/PresentPage";
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
        <Route
          path="issue"
          element={
            <PlaceholderPage
              title="Issue"
              phase={4}
              description="OID4VCI issuance flows and consent views."
            />
          }
        />
        <Route
          path="log"
          element={
            <PlaceholderPage
              title="Transaction log"
              phase={5}
              description="Holder transaction history and export."
            />
          }
        />
        <Route
          path="privacy"
          element={
            <PlaceholderPage
              title="Privacy"
              phase={5}
              description="Data deletion requests and DPA reports."
            />
          }
        />
        <Route path="settings" element={<SettingsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
