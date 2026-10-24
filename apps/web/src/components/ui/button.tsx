import type { ComponentProps } from "react";

type ButtonVariant = "default" | "outline" | "secondary" | "ghost" | "destructive" | "link";
type ButtonSize = "default" | "xs" | "sm" | "lg" | "icon" | "icon-xs" | "icon-sm" | "icon-lg";

const base = "inline-flex shrink-0 select-none items-center justify-center whitespace-nowrap rounded-md border text-xs font-medium transition-colors outline-none focus-visible:ring-2 focus-visible:ring-cyan-400/50 disabled:pointer-events-none disabled:opacity-45 [&_svg]:pointer-events-none [&_svg]:size-3.5 [&_svg]:shrink-0";
const variants: Record<ButtonVariant, string> = {
  default: "border-cyan-400/40 bg-cyan-400/15 text-cyan-100 hover:bg-cyan-400/25",
  outline: "border-slate-700 bg-slate-950/40 text-slate-200 hover:border-slate-500 hover:bg-slate-800/70",
  secondary: "border-slate-700 bg-slate-800 text-slate-100 hover:bg-slate-700",
  ghost: "border-transparent bg-transparent text-slate-300 hover:bg-slate-800 hover:text-white",
  destructive: "border-red-400/30 bg-red-400/10 text-red-200 hover:bg-red-400/20",
  link: "border-transparent bg-transparent text-cyan-300 underline-offset-4 hover:underline",
};
const sizes: Record<ButtonSize, string> = {
  default: "h-8 gap-1.5 px-3",
  xs: "h-6 gap-1 px-2 text-[10px] [&_svg]:size-3",
  sm: "h-7 gap-1.5 px-2.5 text-[11px]",
  lg: "h-9 gap-2 px-3.5",
  icon: "size-8",
  "icon-xs": "size-6 [&_svg]:size-3",
  "icon-sm": "size-7",
  "icon-lg": "size-9",
};

function Button({
  className,
  variant = "default",
  size = "default",
  ...props
}: ComponentProps<"button"> & { variant?: ButtonVariant; size?: ButtonSize }) {
  return (
    <button
      data-slot="button"
      className={[base, variants[variant], sizes[size], className].filter(Boolean).join(" ")}
      {...props}
    />
  )
}

export { Button }
