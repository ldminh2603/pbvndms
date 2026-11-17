import type { NextRequest } from 'next/server'

function resolveWebCoreApiUrl() {
  const configuredUrl = process.env.WEBCORE_API_URL?.trim()

  if (configuredUrl) {
    try {
      return new URL(configuredUrl).origin
    } catch {
      // Fall back to the local WebCore API when the environment value is malformed.
    }
  }

  return 'http://localhost:51130'
}

const webCoreApiUrl = resolveWebCoreApiUrl()

async function forwardAuthRequest(request: NextRequest, action: string) {
  const requestHeaders = new Headers()
  for (const headerName of ['accept', 'authorization', 'content-type', 'cookie', 'x-correlation-id']) {
    const headerValue = request.headers.get(headerName)
    if (headerValue) {
      requestHeaders.set(headerName, headerValue)
    }
  }

  try {
    const upstream = await fetch(`${webCoreApiUrl}/api/auth/${action}`, {
      method: request.method,
      headers: requestHeaders,
      body: request.method === 'GET' || request.method === 'HEAD'
        ? undefined
        : await request.arrayBuffer(),
      redirect: 'manual',
    })

    const responseHeaders = new Headers(upstream.headers)
    responseHeaders.delete('content-encoding')
    responseHeaders.delete('transfer-encoding')
    responseHeaders.delete('content-length')

    const getSetCookie = (upstream.headers as Headers & {
      getSetCookie?: () => string[]
    }).getSetCookie

    if (getSetCookie) {
      responseHeaders.delete('set-cookie')
      for (const cookie of getSetCookie.call(upstream.headers)) {
        responseHeaders.append('set-cookie', cookie)
      }
    }

    if (action === 'me' && upstream.status === 401) {
      return new Response(null, { status: 204 })
    }

    return new Response(upstream.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: responseHeaders,
    })
  } catch {
    return Response.json(
      { message: 'Cannot connect to the WebCore API.' },
      { status: 502 },
    )
  }
}

type RouteContext = { params: Promise<{ action: string }> }

async function handler(request: NextRequest, context: RouteContext) {
  const { action } = await context.params
  return forwardAuthRequest(request, action)
}

export const GET = handler
export const POST = handler
