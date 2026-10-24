import {
  ArrowDownRight,
  ArrowRight,
  Braces,
  CircleHelp,
  FileCode2,
  GitBranch,
  LockKeyhole,
  ScanLine,
  Sparkles,
} from "lucide-react";
import { Brand } from "@/components/brand";
import { UploadPanel } from "@/components/upload-panel";

export default function Home() {
  return (
    <main className="landing-shell">
      <header className="landing-topbar">
        <Brand />
        <div className="topbar-center"><span className="topbar-rule" /> ENGINEERING WORKSPACE <span className="topbar-rule" /></div>
        <a className="help-link" href="/swagger-ui/" target="_blank" rel="noreferrer"><CircleHelp size={16} /> API</a>
      </header>

      <section className="landing-hero">
        <div className="hero-copy">
          <div className="eyebrow"><span className="eyebrow-dot" /> JAVA BLUEPRINTS <span className="eyebrow-slash">/</span> MILESTONE 01</div>
          <h1>Посмотрите<br /><em>на код</em> по-новому.</h1>
          <p className="hero-description">Начните с исходника. Подготовьте его к анализу, который покажет структуру программы и поможет работать с ней осознанно.</p>

          <UploadPanel />

          <div className="privacy-line"><LockKeyhole size={14} /><span>Гостевая сессия · без регистрации · исходник доступен только в этой сессии</span></div>
        </div>

        <div className="hero-visual" aria-label="Этапы обработки Java-исходника">
          <div className="visual-topline"><span>PIPELINE / 01</span><span>JAVA SOURCE</span></div>
          <div className="visual-stage visual-stage--active">
            <span className="stage-icon"><FileCode2 size={19} /></span>
            <div className="stage-copy"><small>ВХОДНЫЕ ДАННЫЕ</small><strong>Исходный файл</strong><span>Один .java · UTF-8</span></div>
            <span className="stage-status stage-status--ready">READY</span>
          </div>
          <div className="pipeline-link"><span /><ArrowDownRight size={17} /><span /></div>
          <div className="visual-stage visual-stage--active">
            <span className="stage-icon"><Braces size={20} /></span>
            <div className="stage-copy"><small>JDT PARSER</small><strong>Разбор и диагностика</strong><span>Подключён к API · AST и семантика</span></div>
            <span className="stage-status stage-status--ready">READY</span>
          </div>
          <div className="pipeline-link pipeline-link--muted"><span /><ArrowDownRight size={17} /><span /></div>
          <div className="visual-stage visual-stage--later">
            <span className="stage-icon"><GitBranch size={19} /></span>
            <div className="stage-copy"><small>СЛЕДУЮЩИЙ ЭТАП</small><strong>CFG и визуальный граф</strong><span>Viewer готов · API endpoint подключается</span></div>
            <span className="stage-status stage-status--later">M2</span>
          </div>
          <div className="visual-footnote"><ScanLine size={15} /><span>Текущий экран работает с настоящим API M1</span><Sparkles size={14} /></div>
          <span className="visual-crosshair visual-crosshair--one" />
          <span className="visual-crosshair visual-crosshair--two" />
        </div>
      </section>

      <section className="landing-bottom">
        <div className="bottom-index"><span>01</span><i /> WORKSPACE</div>
        <p>Путь начинается с чистого исходника.<br /><strong>Без выдуманных результатов и нарисованной аналитики.</strong></p>
        <a className="bottom-link" href="/api/v1/openapi.json" target="_blank" rel="noreferrer">Контракт API <ArrowRight size={16} /></a>
      </section>
    </main>
  );
}
