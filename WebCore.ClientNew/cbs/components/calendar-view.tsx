'use client'
import { useEffect, useRef } from 'react'
import { Calendar } from '@fullcalendar/core'
import dayGridPlugin from '@fullcalendar/daygrid'
import interactionPlugin, { Draggable } from '@fullcalendar/interaction'
import listPlugin from '@fullcalendar/list'
import timeGridPlugin from '@fullcalendar/timegrid'
const isoDate = (d: Date) => d.toISOString().slice(0, 10)
export function CalendarView() {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!ref.current) return
    const external = document.getElementById('external-events')
    if (external) new Draggable(external, { itemSelector: '.draggable-event', eventData: el => ({ title: el.textContent?.trim(), color: el.dataset.color }) })
    const today = new Date(); const offset = (n: number) => { const d = new Date(today); d.setDate(today.getDate() + n); return isoDate(d) }
    const calendar = new Calendar(ref.current, { plugins: [dayGridPlugin, timeGridPlugin, listPlugin, interactionPlugin], initialView: 'dayGridMonth', headerToolbar: { start: 'prev,next today', center: 'title', end: 'dayGridMonth,timeGridWeek,timeGridDay,listWeek' }, height: 700, editable: true, droppable: true, dayMaxEvents: 3, events: [{ title: 'Quarterly planning', start: offset(-2), color: '#0d6efd' }, { title: 'Onboarding session', start: offset(1), color: '#198754' }] })
    calendar.render(); return () => calendar.destroy()
  }, [])
  return <div id="calendar" ref={ref} />
}
