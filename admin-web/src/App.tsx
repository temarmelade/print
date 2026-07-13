import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider, useAuth } from "./auth/AuthContext.tsx";
import { AppLayout } from "./components/AppLayout.tsx";
import { RequireAuth, RequireModule, RedirectIfAuthed } from "./components/Guards.tsx";
import { landingPathFor } from "./lib/permissions.ts";
import { LoginPage } from "./pages/LoginPage.tsx";
import { TerminalsPage, AlertsPage, AnalyticsPage } from "./pages/modules.tsx";
import { DashboardPage } from "./pages/DashboardPage.tsx";
import { AccessPage } from "./pages/AccessPage.tsx";
import { TransactionsPage } from "./pages/TransactionsPage.tsx";
import { MediaPage } from "./pages/MediaPage.tsx";
import { ForbiddenPage, NotFoundPage } from "./pages/system.tsx";

/** Стартовый редирект «/» на первый доступный роли модуль. */
function Landing() {
  const { user } = useAuth();
  return <Navigate to={user ? landingPathFor(user.role) : "/login"} replace />;
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<RedirectIfAuthed><LoginPage /></RedirectIfAuthed>} />

          <Route element={<RequireAuth><AppLayout /></RequireAuth>}>
            <Route index element={<Landing />} />
            <Route path="/dashboard"    element={<RequireModule moduleId="dashboard"><DashboardPage /></RequireModule>} />
            <Route path="/terminals"    element={<RequireModule moduleId="terminals"><TerminalsPage /></RequireModule>} />
            <Route path="/alerts"       element={<RequireModule moduleId="alerts"><AlertsPage /></RequireModule>} />
            <Route path="/analytics"    element={<RequireModule moduleId="analytics"><AnalyticsPage /></RequireModule>} />
            <Route path="/transactions" element={<RequireModule moduleId="transactions"><TransactionsPage /></RequireModule>} />
            <Route path="/media"        element={<RequireModule moduleId="media"><MediaPage /></RequireModule>} />
            <Route path="/access"       element={<RequireModule moduleId="access"><AccessPage /></RequireModule>} />
            <Route path="/forbidden"    element={<ForbiddenPage />} />
            <Route path="*"             element={<NotFoundPage />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}
