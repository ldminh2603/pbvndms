import { DemoLayout } from '@/components/demo-layout'

/** DMS DevExtreme PoC, rendered inside the selected component shell. */
export default function DevExtremeTestLayout({ children }: { children: React.ReactNode }) {
  return <DemoLayout fixedHeader fixedSidebar>{children}</DemoLayout>
}
