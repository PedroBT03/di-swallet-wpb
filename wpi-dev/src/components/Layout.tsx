import { NavLink, Outlet } from "react-router-dom";
import { AuthStatus } from "./AuthStatus";

const navItems = [
  { to: "/", label: "Health", end: true },
  { to: "/wallet", label: "Wallet" },
  { to: "/present", label: "Present" },
  { to: "/issue", label: "Issue" },
  { to: "/log", label: "Log" },
  { to: "/privacy", label: "Privacy" },
  { to: "/ops", label: "Ops" },
  { to: "/pseudonyms", label: "Pseudonyms" },
  { to: "/settings", label: "Settings" },
] as const;

export function Layout() {
  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="app-header__brand">
          <span className="app-header__title">WPI Dev</span>
          <span className="app-header__subtitle">DI-Swallet lab UI</span>
        </div>
        <AuthStatus />
        <nav className="app-nav" aria-label="Main">
          {navItems.map(({ to, label, ...rest }) => (
            <NavLink
              key={to}
              to={to}
              className={({ isActive }) =>
                isActive ? "app-nav__link app-nav__link--active" : "app-nav__link"
              }
              {...rest}
            >
              {label}
            </NavLink>
          ))}
        </nav>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  );
}
