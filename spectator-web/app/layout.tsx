import "./globals.css";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "LazyFitBike Live Spectator Companion",
  description: "Real-time live telemetry stream & 3D track spectator companion for LazyFitBike riders",
  themeColor: "#09090B",
  viewport: {
    width: "device-width",
    initialScale: 1,
    maximumScale: 1
  }
};

export default function RootLayout({
  children
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body className="bg-[#09090B] text-zinc-100 min-h-screen">
        {children}
      </body>
    </html>
  );
}
