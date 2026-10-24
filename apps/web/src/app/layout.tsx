import type { Metadata } from "next";
import { IBM_Plex_Mono, Manrope } from "next/font/google";
import "@xyflow/react/dist/style.css";
import "./globals.css";

const manrope = Manrope({ variable: "--font-ui", subsets: ["latin", "cyrillic"] });
const plexMono = IBM_Plex_Mono({ variable: "--font-code", subsets: ["latin", "cyrillic"], weight: ["400", "500", "600"] });

export const metadata: Metadata = {
  title: {
    default: "Java Blueprints — рабочее пространство",
    template: "%s · Java Blueprints",
  },
  description: "Рабочее пространство для изучения и анализа Java-кода.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ru" className={`${manrope.variable} ${plexMono.variable}`}>
      <body>{children}</body>
    </html>
  );
}
