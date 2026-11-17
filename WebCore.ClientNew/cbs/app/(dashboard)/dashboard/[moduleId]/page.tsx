'use client'

import { useParams } from 'next/navigation'
import { RuntimeDashboardModule } from '@/components/runtime-dashboard-module'

export default function RuntimeDashboardPage() {
  const params = useParams<{ moduleId: string }>()
  return <RuntimeDashboardModule moduleId={params.moduleId ?? ''} />
}
