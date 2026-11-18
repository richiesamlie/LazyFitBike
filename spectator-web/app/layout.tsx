import "./globals.css";
import type { Metadata, Viewport } from "next";
import { Plus_Jakarta_Sans } from "next/font/google";

const plusJakartaSans = Plus_Jakarta_Sans({
  subsets: ["latin"],
  weight: ["500", "600", "700", "800"],
  display: "swap"
});

export const metadata: Metadata = {
  title: "LazyFitBike Live Spectator Cockpit",
  description:
    "Real-time cycling telemetry stream, course elevation profile, and spectator cheers for LazyFitBike riders."
};

export const viewport: Viewport = {
  themeColor: "#0B1510",
  width: "device-width",
  initialScale: 1
};

export default function RootLayout({
  children
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body className={plusJakartaSans.className}>{children}</body>
    </html>
  );
}
