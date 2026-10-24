import Link from "next/link";

export function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <Link className="inline-flex min-w-max items-center gap-2.5" href="/">
      <span className={`grid grid-cols-2 gap-[3px] rounded-md border border-slate-700 bg-slate-900 ${compact ? "size-[27px] p-[5px]" : "size-[30px] p-1.5"}`} aria-hidden="true">
        <i className="rounded-[1px] bg-blue-400" />
        <i className="rounded-[1px] bg-cyan-400" />
        <i className="rounded-[1px] bg-blue-400 opacity-45" />
        <i className="rounded-[1px] bg-blue-400" />
      </span>
      <span className="flex flex-col gap-[3px] leading-none max-[1120px]:hidden">
        <strong className={`font-bold tracking-[-0.045em] text-slate-100 ${compact ? "text-xs" : "text-[13px]"}`}>blueprints</strong>
        <small className="font-mono text-[7px] font-semibold tracking-[0.12em] text-slate-500">JAVA WORKSPACE</small>
      </span>
    </Link>
  );
}
