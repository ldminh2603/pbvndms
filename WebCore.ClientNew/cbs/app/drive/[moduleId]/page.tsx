'use client'

import { useParams } from 'next/navigation'
import { RuntimeDriveModule } from '@/components/runtime-drive-module'

export default function RuntimeDrivePage() {
  const params = useParams<{ moduleId: string }>()
  return <RuntimeDriveModule moduleId={params.moduleId ?? ''} />
}
