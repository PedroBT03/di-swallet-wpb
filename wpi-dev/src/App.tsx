import { Navigate, Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { HealthPage } from "./pages/HealthPage";
import { OnboardingPage } from "./pages/OnboardingPage";
import { LoginPage } from "./pages/LoginPage";
import { PlaceholderPage } from "./pages/PlaceholderPage";
import { SettingsPage } from "./pages/SettingsPage";

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<HealthPage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="onboarding" element={<OnboardingPage />} />
        <Route
          path="wallet"
          element={
            <PlaceholderPage
              title="Wallet"
              phase={2}
              description="Credential storage, disclosures, and wallet management."
            />
          }
        />
        <Route
          path="present"
          element={
            <PlaceholderPage
              title="Present"
              phase={3}
              description="OID4VP presentation flows and consent views."
            />
          }
        />
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
