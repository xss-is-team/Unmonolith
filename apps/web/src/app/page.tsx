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
    title: "Список методов",
    description: "Имена и позиции в исходнике.",
  },
  {
    icon: ScanSearch,
    title: "Диагностики с привязкой к коду",
    description: "Откройте исходник прямо с нужной строки.",
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
            Загрузите один Java-файл. Получите список методов и диагностики с переходом к исходному коду.
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
          <div className="outcome-next">
            <span className="outcome-number">03</span>
            <span className="outcome-copy"><strong>Граф потока управления</strong><small>Следующий этап · M2</small></span>
            <ArrowRight size={16} />
          </div>
          <p className="outcomes-note"><FileCode2 size={15} /> Анализ не меняет исходный файл.</p>
        </aside>
      </section>

      <footer className="landing-bottom">
        <span>JAVA BLUEPRINTS <i>·</i> M1</span>
        <a className="bottom-link" href="/api/v1/openapi.json" target="_blank" rel="noreferrer">Контракт API <ArrowRight size={16} /></a>
      </footer>
    </main>
  );
}
