import type { Metadata } from 'next'
import 'bootstrap-icons/font/bootstrap-icons.min.css'
import 'overlayscrollbars/styles/overlayscrollbars.css'
import 'apexcharts/dist/apexcharts.css'
import 'jsvectormap/dist/jsvectormap.min.css'
import 'tabulator-tables/dist/css/tabulator_bootstrap5.min.css'
import 'quill/dist/quill.snow.css'
import 'flatpickr/dist/flatpickr.min.css'
import 'tom-select/dist/css/tom-select.bootstrap5.css'
import '@fontsource/source-sans-3'
import 'bootstrap/dist/css/bootstrap.min.css'
import '@adminlte/react/css'
import './globals.css'
import 'devextreme/dist/css/dx.common.css'
import 'devextreme/dist/css/dx.light.css'
import { SubpathLinks } from '@/components/subpath-links'
import { InertLinks } from '@/components/inert-links'
import { AppAuthGate } from '@/components/app-auth-gate'
import { BootstrapToastTriggers } from '@/components/bootstrap-toast-triggers'
import { LocalVendorScripts } from '@/components/local-vendor-scripts'

export const metadata: Metadata = {
  title: { default: 'Web Public Bank', template: '%s | Web Public Bank' },
  applicationName: 'Web Public Bank',
  icons: { icon: [{ url: '/assets/img/pbbank.webp', type: 'image/webp' }] },
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return <html lang="en" data-scroll-behavior="smooth"><body suppressHydrationWarning><AppAuthGate>{children}</AppAuthGate><SubpathLinks /><InertLinks /><BootstrapToastTriggers /><LocalVendorScripts /></body></html>
}
