import { DemoLayout } from '@/components/demo-layout'

// Title template for the whole dashboard group: page titles become
// "<page> · Web Public Bank"; the home page (no own title) uses the default.
export const metadata = {
  title: { default: 'Web Public Bank', template: '%s · Web Public Bank' },
}

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  return (
    <DemoLayout fixedHeader fixedSidebar>
      {children}
    </DemoLayout>
  )
}
