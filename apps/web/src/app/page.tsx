import {
  CircleHelp,
  ShieldCheck,
} from "lucide-react";
import { Brand } from "@/components/brand";
import { UploadPanel } from "@/components/upload-panel";

export default function Home() {
  return (
    <main className="flex min-h-svh flex-col bg-[#070b14] px-[clamp(24px,7vw,104px)] max-[700px]:px-4 [background-image:linear-gradient(rgba(96,165,250,.035)_1px,transparent_1px),linear-gradient(90deg,rgba(96,165,250,.035)_1px,transparent_1px)] [background-size:28px_28px]">
      <header className="flex h-[58px] items-center justify-between border-b border-slate-800">
        <Brand />
        <div className="font-mono text-[9px] font-semibold tracking-[0.11em] text-slate-400 max-[700px]:hidden">JAVA <i className="text-cyan-400 not-italic">·</i> ПРОВЕРКА ИСХОДНИКА</div>
        <a className="inline-flex items-center gap-1.5 text-[11px] text-slate-400 hover:text-cyan-400" href="/swagger-ui/" target="_blank" rel="noreferrer">
          <CircleHelp size={16} /> API
        </a>
      </header>

      <section className="grid w-full max-w-[760px] flex-1 place-items-center self-center py-14">
        <div className="w-full rounded-xl border border-slate-800 bg-slate-950/90 p-[clamp(28px,5vw,56px)] shadow-[0_28px_90px_rgba(0,0,0,.35)]">
          <div className="font-mono text-[9px] font-semibold tracking-[0.11em] text-cyan-400">РАБОЧЕЕ ПРОСТРАНСТВО / JAVA</div>
          <h1 className="my-[22px] text-[clamp(44px,7vw,74px)] font-semibold leading-[.98] tracking-[-0.075em] text-slate-50">Разберите<br /><em className="font-medium not-italic text-cyan-400">Java-код.</em></h1>
          <p className="m-0 max-w-[590px] text-sm leading-7 text-slate-400">
            Загрузите монолит: анализ начнётся автоматически. Смотрите код как граф, находите узкие места по Big O, оптимизируйте и разбирайте его на классы, а затем докажите в Docker, что поведение не изменилось.
          </p>
          <UploadPanel />
          <div className="mt-[18px] inline-flex items-center gap-1.5 font-mono text-[9px] text-slate-500"><ShieldCheck className="text-green-400" size={15} /><span>Исходник доступен только в вашей гостевой сессии</span></div>
        </div>
      </section>

      <footer className="flex min-h-[58px] items-center justify-between border-t border-slate-800 font-mono text-[9px] font-semibold tracking-[0.11em] text-slate-400">
        <span>JAVA BLUEPRINTS <i className="text-cyan-400 not-italic">·</i> END-TO-END WORKSPACE</span>
        <a className="hover:text-cyan-400" href="/api/v1/openapi.json" target="_blank" rel="noreferrer">Контракт API</a>
      </footer>
    </main>
  );
}
