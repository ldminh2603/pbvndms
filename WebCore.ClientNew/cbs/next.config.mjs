import path from 'node:path'
import { fileURLToPath } from 'node:url'

// Subpath deploy is gated behind EXPORT so local `pnpm cbs` (dev) stays at the
// domain root. `EXPORT=true next build` produces a static export configured for
// https://adminlte.io/themes/next-react/.
const isExport = process.env.EXPORT === 'true'
const base = isExport ? '/themes/next-react' : ''
const projectRoot = path.dirname(fileURLToPath(import.meta.url))

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  images: { unoptimized: true },
  outputFileTracingRoot: path.join(projectRoot, '..'),
  env: { NEXT_PUBLIC_BASE_PATH: base },
  ...(isExport
    ? { output: 'export', trailingSlash: true, assetPrefix: base }
    : { output: 'standalone' }),
}

export default nextConfig
