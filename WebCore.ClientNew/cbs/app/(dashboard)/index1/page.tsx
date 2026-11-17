'use client'

import { AppContent, ApexChart, Card, SmallBox } from '@adminlte/react'
import { revenueChartConfig, revenueChartSeries } from '@/lib/chart-data'

export default function DashboardV1Page() {
  return <AppContent title="Dashboard v1" breadcrumbs={[{ label: 'Home', href: '/' }, { label: 'Dashboard v1' }]}>
    <div className="row">
      <div className="col-lg-3 col-6"><SmallBox title="150" text="New Orders" theme="primary" icon={<i className="bi bi-cart-fill small-box-icon" />} url="#" /></div>
      <div className="col-lg-3 col-6"><SmallBox title="53%" text="Bounce Rate" theme="success" icon={<i className="bi bi-bar-chart-fill small-box-icon" />} url="#" /></div>
      <div className="col-lg-3 col-6"><SmallBox title="44" text="User Registrations" theme="warning" icon={<i className="bi bi-person-plus-fill small-box-icon" />} url="#" /></div>
      <div className="col-lg-3 col-6"><SmallBox title="65" text="Unique Visitors" theme="danger" icon={<i className="bi bi-pie-chart-fill small-box-icon" />} url="#" /></div>
    </div>
    <div className="row mt-4"><div className="col-lg-7"><Card title="Sales Value" collapsible maximizable><ApexChart id="dashboard-v1-revenue" series={revenueChartSeries} config={revenueChartConfig} /></Card></div>
      <div className="col-lg-5"><Card title="Quick overview"><dl className="row mb-0"><dt className="col-7">Orders today</dt><dd className="col-5 text-end">150</dd><dt className="col-7">New members</dt><dd className="col-5 text-end">44</dd><dt className="col-7">Visitors</dt><dd className="col-5 text-end">65</dd></dl></Card></div>
    </div>
  </AppContent>
}
