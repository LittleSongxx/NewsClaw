export function parseJsonArray(json: string | null | undefined): string[] {
  if (!json) return []
  try {
    return JSON.parse(json) || []
  } catch {
    return []
  }
}

export function parseFindings(json: string | null): any[] {
  if (!json) return []
  try {
    return JSON.parse(json) || []
  } catch {
    return []
  }
}

export function formatTime(time: string): string {
  if (!time) return ''
  try {
    const d = new Date(time)
    return d.toLocaleString()
  } catch {
    return time
  }
}

export function truncateConvId(id: string | null): string {
  if (!id) return ''
  return id.length > 20 ? id.substring(0, 20) + '...' : id
}
