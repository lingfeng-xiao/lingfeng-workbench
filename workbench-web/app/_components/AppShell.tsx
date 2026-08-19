import Link from "next/link";
import type { ReactNode } from "react";

const NAVIGATION = [
  { href: "/", label: "概览" },
  { href: "/interactions", label: "待处理" },
  { href: "/nodes", label: "执行节点" },
] as const;

export function AppShell({
  currentPath,
  eyebrow,
  title,
  description,
  children,
}: {
  currentPath: string;
  eyebrow: string;
  title: string;
  description: string;
  children: ReactNode;
}) {
  return (
    <div className="app-frame">
      <header className="topbar">
        <Link className="brand" href="/" aria-label="Lingfeng Workbench 首页">
          <span className="brand-mark" aria-hidden="true">LF</span>
          <span>
            <strong>Lingfeng</strong>
            <small>Workbench</small>
          </span>
        </Link>
        <nav aria-label="主导航">
          {NAVIGATION.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              aria-current={currentPath === item.href ? "page" : undefined}
            >
              {item.label}
            </Link>
          ))}
        </nav>
        <div className="privacy-mark" title="由 Sites 私有访问策略保护">
          <span aria-hidden="true" />
          私有只读
        </div>
      </header>

      <main>
        <header className="hero">
          <p>{eyebrow}</p>
          <h1>{title}</h1>
          <div className="hero-rule" aria-hidden="true" />
          <span>{description}</span>
        </header>
        {children}
      </main>

      <footer>
        <span>Control state only</span>
        <span>完整证据留在执行电脑</span>
      </footer>
    </div>
  );
}
