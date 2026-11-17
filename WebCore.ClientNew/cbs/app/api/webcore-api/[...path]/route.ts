import type { NextRequest } from 'next/server'

function resolveWebCoreApiUrl() {
  const configuredUrl = process.env.WEBCORE_API_URL?.trim()
  if (configuredUrl) {
    try {
      return new URL(configuredUrl).origin
    } catch {
      // Use the local API if a shell command supplied a malformed URL value.
    }
  }

  return 'http://localhost:51130'
}

const webCoreApiUrl = resolveWebCoreApiUrl()
const forwardedHeaders = ['accept', 'accept-language', 'authorization', 'content-type', 'cookie', 'idempotency-key', 'selected_component', 'x-correlation-id']

type RouteContext = { params: Promise<{ path: string[] }> }

async function forward(request: NextRequest, context: RouteContext) {
  const { path } = await context.params
  const headers = new Headers()
  forwardedHeaders.forEach(name => {
    const value = request.headers.get(name)
    if (value) headers.set(name, value)
  })

  // Browser authentication is stored in the HttpOnly access_token cookie.
  // The Spring module endpoints also accept Bearer tokens (as used by Postman),
  // so promote that cookie when the browser did not send Authorization itself.
  const accessToken = request.cookies.get('access_token')?.value
  if (accessToken && !headers.has('authorization')) {
    headers.set('authorization', `Bearer ${accessToken}`)
  }

  try {
    const upstream = await fetch(`${webCoreApiUrl}/api/${path.map(encodeURIComponent).join('/')}${request.nextUrl.search}`, {
      method: request.method,
      headers,
      body: request.method === 'GET' || request.method === 'HEAD' ? undefined : await request.arrayBuffer(),
      redirect: 'manual',
    })
    const responseHeaders = new Headers(upstream.headers)
    responseHeaders.delete('content-encoding')
    responseHeaders.delete('content-length')
    responseHeaders.delete('transfer-encoding')

    const getSetCookie = (upstream.headers as Headers & { getSetCookie?: () => string[] }).getSetCookie
    if (getSetCookie) {
      responseHeaders.delete('set-cookie')
      for (const cookie of getSetCookie.call(upstream.headers)) {
        responseHeaders.append('set-cookie', cookie)
      }
    }

    return new Response(upstream.body, { status: upstream.status, statusText: upstream.statusText, headers: responseHeaders })
  } catch {
    return Response.json({ message: 'Cannot connect to the WebCore API.' }, { status: 502 })
  }
}

export const GET = forward
export const POST = forward
export const PUT = forward
export const PATCH = forward
export const DELETE = forward
