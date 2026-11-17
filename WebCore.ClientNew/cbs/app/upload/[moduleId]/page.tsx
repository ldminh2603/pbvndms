'use client'

import { useParams } from 'next/navigation'
import { RuntimeUploadModule } from '@/components/runtime-upload-module'

export default function RuntimeUploadPage() {
  const params = useParams<{ moduleId: string }>()
  return <RuntimeUploadModule moduleId={params.moduleId ?? ''} />
}
