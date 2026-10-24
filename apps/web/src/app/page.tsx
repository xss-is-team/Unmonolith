import {
  ArrowRight,
  CircleHelp,
  FileCode2,
  ListTree,
  ScanSearch,
  ShieldCheck,
} from "lucide-react";
import { Brand } from "@/components/brand";
import { UploadPanel } from "@/components/upload-panel";

const outcomes = [
  {
    icon: ListTree,
    title: "JDT: AST, типы и диагностики",
    description: "Методы и ошибки привязаны к UTF-16 диапазонам исходника.",
  },
  {
    icon: ScanSearch,
    title: "CFG и Big O",
    description: "Реальные графы методов, оценки и рекомендации parser.",
  },
  {
    icon: ListTree,
    title: "Изменение и проверка",
    description: "ASTRewrite, diff ревизий и Docker-сравнение поведения.",
  },
];

export default function Home() {
  return (
    <main className="landing-shell">
      <header className="landing-topbar">
        <Brand />
        <div className="landing-topbar-meta">JAVA <i>·</i> ПРОВЕРКА ИСХОДНИКА</div>
        <a className="help-link" href="/swagger-ui/" target="_blank" rel="noreferrer">
          <CircleHelp size={16} /> API
        </a>
      </header>

      <section className="landing-hero">
        <div className="hero-copy">
          <div className="eyebrow">РАБОЧЕЕ ПРОСТРАНСТВО / JAVA</div>
          <h1>Разберите<br /><em>Java-код.</em></h1>
          <p className="hero-description">
            Загрузите Java-файл: анализ начнётся автоматически. Изучайте код и CFG, меняйте условия через ASTRewrite и сравнивайте поведение ревизий.
          </p>
          <UploadPanel />
          <div className="privacy-line"><ShieldCheck size={15} /><span>Исходник доступен только в вашей гостевой сессии</span></div>
        </div>

        <aside className="outcomes-card" aria-label="Возможности рабочего пространства">
          <div className="outcomes-heading">
            <span>ПОСЛЕ ЗАГРУЗКИ</span>
            <span>01 — 03</span>
          </div>
          {outcomes.map(({ icon: Icon, title, description }, index) => (
            <div className="outcome-row" key={title}>
              <span className="outcome-number">0{index + 1}</span>
              <span className="outcome-icon"><Icon size={18} /></span>
              <span className="outcome-copy"><strong>{title}</strong><small>{description}</small></span>
            </div>
          ))}
          <p className="outcomes-note"><FileCode2 size={15} /> Никаких демонстрационных графов: интерфейс показывает только ответы parser/API.</p>
        </aside>
      </section>

      <footer className="landing-bottom">
        <span>JAVA BLUEPRINTS <i>·</i> END-TO-END WORKSPACE</span>
        <a className="bottom-link" href="/api/v1/openapi.json" target="_blank" rel="noreferrer">Контракт API <ArrowRight size={16} /></a>
      </footer>
    </main>
  );
}
