import axios from 'axios'

export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
})

let isRefreshing = false
let refreshSubscribers: Array<() => void> = []

function subscribeToRefresh(cb: () => void) {
  refreshSubscribers.push(cb)
}

function notifyRefreshSubscribers() {
  refreshSubscribers.forEach(cb => cb())
  refreshSubscribers = []
}

api.interceptors.response.use(
  res => res,
  async error => {
    const originalRequest = error.config as typeof error.config & { _retry?: boolean }

    if (
      error.response?.status === 401 &&
      !originalRequest._retry &&
      !originalRequest.url?.includes('/auth/')
    ) {
      if (isRefreshing) {
        return new Promise(resolve => {
          subscribeToRefresh(() => resolve(api(originalRequest!)))
        })
      }

      originalRequest._retry = true
      isRefreshing = true

      try {
        await api.post('/auth/refresh')
        notifyRefreshSubscribers()
        return api(originalRequest!)
      } catch {
        window.location.href =
          '/login?redirect=' +
          encodeURIComponent(window.location.pathname + window.location.search)
        return Promise.reject(error)
      } finally {
        isRefreshing = false
      }
    }

    return Promise.reject(error)
  }
)
