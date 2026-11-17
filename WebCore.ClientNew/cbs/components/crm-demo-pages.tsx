'use client'

import { useMemo, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'

type Contact = {
  id: number; name: string; position: string; company: string
  status: 'Salaried' | 'Commission' | 'Terminated'; assignedTo: string
  phone: string; email: string; address: string; city: string; state: string; zip: string; image: string
}

const contacts: Contact[] = [
  { id: 1, name: 'Amelia Harper', position: 'Network Admin', company: 'ACME', status: 'Salaried', assignedTo: 'John Heart', phone: '(213) 555-4276', email: 'amelia@dx-email.com', address: '645 Prospect Crescent', city: 'Pasadena', state: 'CA', zip: '91103', image: '/assets/img/user1-128x128.jpg' },
  { id: 2, name: 'Antony Remmner', position: 'Support Assistant', company: 'Clicker', status: 'Salaried', assignedTo: 'Samantha Bright', phone: '(310) 555-6625', email: 'anthony@dx-email.com', address: '1542 S Beacon St.', city: 'Los Angeles', state: 'CA', zip: '90031', image: '/assets/img/user2-160x160.jpg' },
  { id: 12, name: 'Arthur Miller', position: 'CTO', company: 'Super Mart of the West', status: 'Salaried', assignedTo: 'Samantha Bright', phone: '(310) 555-8583', email: 'arthur@dx-email.com', address: '3800 Homer St.', city: 'Los Angeles', state: 'CA', zip: '90031', image: '/assets/img/user3-128x128.jpg' },
  { id: 3, name: 'Barb Banks', position: 'Support Manager', company: 'StereoShack', status: 'Salaried', assignedTo: 'Samantha Bright', phone: '(310) 555-3355', email: 'barbarab@dx-email.com', address: '45 Main Street', city: 'Pasadena', state: 'CA', zip: '91103', image: '/assets/img/user4-128x128.jpg' },
  { id: 4, name: 'Bart Arnaz', position: 'Director of Engineering', company: 'ElectrixMax', status: 'Salaried', assignedTo: 'John Heart', phone: '(714) 555-2000', email: 'barta@dx-email.com', address: '93 Garden St.', city: 'Irvine', state: 'CA', zip: '92614', image: '/assets/img/user5-128x128.jpg' },
  { id: 5, name: 'Brad Farkus', position: 'Engineer', company: 'Walters', status: 'Terminated', assignedTo: 'John Heart', phone: '(213) 555-3626', email: 'bradf@dx-email.com', address: '10 Lake Avenue', city: 'Pasadena', state: 'CA', zip: '91101', image: '/assets/img/user6-128x128.jpg' },
  { id: 6, name: 'Brad Jameson', position: 'Programmer', company: 'Electronics Depot', status: 'Salaried', assignedTo: 'John Heart', phone: '(818) 555-5389', email: 'bradleyj@dx-email.com', address: '23 Ocean Ave.', city: 'Burbank', state: 'CA', zip: '91502', image: '/assets/img/user7-128x128.jpg' },
]

function Status({ value }: { value: Contact['status'] }) {
  const color = value === 'Terminated' ? 'text-danger' : value === 'Commission' ? 'text-info' : 'text-success'
  return <span className={color}><i className="bi bi-circle-fill small me-1" />{value}</span>
}

function Drawer({ contact, close }: { contact: Contact; close: () => void }) {
  const router = useRouter()
  return <aside className="border-start bg-body p-3" style={{ width: 320, flexShrink: 0 }}>
    <div className="d-flex gap-2 align-items-center mb-3"><strong className="text-truncate">{contact.name}</strong><Status value={contact.status} /><button className="btn-close btn-sm ms-auto" onClick={close} /></div>
    <img src={contact.image} alt={contact.name} className="border rounded mb-3 d-block mx-auto object-fit-cover" width="156" height="156" />
    <div className="small">
      <p><span className="d-block text-body-secondary">Company</span><a href="#">{contact.company}</a></p>
      <p><span className="d-block text-body-secondary">Position</span>{contact.position}</p>
      <p><span className="d-block text-body-secondary">Assigned to</span><a href="#">{contact.assignedTo}</a></p>
    </div>
    <div className="border-top border-bottom py-3 small">
      <p><i className="bi bi-telephone me-3 text-secondary" />{contact.phone}</p>
      <p><i className="bi bi-envelope me-3 text-secondary" />{contact.email}</p>
      <p className="mb-0"><i className="bi bi-house me-3 text-secondary" />{contact.address}</p>
    </div>
    <div className="d-flex gap-2 py-3 border-bottom"><span className="small me-auto">ACTIONS</span><button className="btn btn-primary btn-sm"><i className="bi bi-pencil me-1" />EDIT</button><button className="btn btn-outline-secondary btn-sm" onClick={() => router.push(`/static/S092?id=${contact.id}`)}>DETAILS</button></div>
    <div className="mt-3 border rounded p-3 small"><strong>Opportunities</strong><p className="mt-3 mb-2">Summer Discount Offer<br />$3,905</p><p className="mb-0">Hospital Conference Room Set<br />$933</p></div>
  </aside>
}

export function CrmContactList() {
  const [selected, setSelected] = useState<Contact | null>(null)
  const [drawerContact, setDrawerContact] = useState<Contact | null>(null)
  const [query, setQuery] = useState('')
  const visible = useMemo(() => contacts.filter(item => [item.name, item.company, item.email].join(' ').toLowerCase().includes(query.toLowerCase())), [query])
  return <div className="container-fluid py-3">
    <div className="d-flex align-items-center gap-2 mb-3">
      <h1 className="h4 mb-0">Contacts</h1>
      <div className="ms-auto d-flex gap-2"><button className="btn btn-primary btn-sm"><i className="bi bi-plus-lg me-1" />ADD CONTACT</button><button className="btn btn-link btn-sm text-decoration-none text-body"><i className="bi bi-pencil me-1" />EDIT</button><button className="btn btn-link btn-sm text-decoration-none text-body"><i className="bi bi-trash me-1" />DELETE</button><input className="form-control form-control-sm" style={{ width: 180 }} placeholder="Contact Search" value={query} onChange={event => setQuery(event.target.value)} /></div>
    </div>
    <div className="border rounded d-flex overflow-hidden bg-body">
      <div className="flex-grow-1 overflow-auto"><table className="table table-hover align-middle mb-0"><thead><tr><th className="ps-3"><input className="form-check-input" type="checkbox" /></th><th>Name <i className="bi bi-arrow-up" /></th><th>Company <i className="bi bi-funnel" /></th><th>Status <i className="bi bi-funnel" /></th><th>Assigned to <i className="bi bi-funnel" /></th><th>Phone <i className="bi bi-funnel" /></th><th>Email <i className="bi bi-funnel" /></th></tr></thead><tbody>
        {visible.map(contact => <tr key={contact.id} className={selected?.id === contact.id ? 'table-info' : ''} onClick={() => setSelected(contact)} onDoubleClick={() => setDrawerContact(contact)} style={{ cursor: 'pointer' }}><td className="ps-3"><input className="form-check-input" type="checkbox" onClick={event => event.stopPropagation()} /></td><td><strong>{contact.name}</strong><small className="d-block text-body-secondary">{contact.position}</small></td><td>{contact.company}</td><td><Status value={contact.status} /></td><td>{contact.assignedTo}</td><td>{contact.phone}</td><td>{contact.email}</td></tr>)}
      </tbody></table></div>
      {drawerContact && <Drawer contact={drawerContact} close={() => setDrawerContact(null)} />}
    </div>
    <p className="small text-body-secondary mt-2 mb-0">Double-click một dòng để mở thông tin chi tiết bên phải.</p>
  </div>
}

export function CrmContactDetails() {
  const router = useRouter(); const params = useSearchParams()
  const contact = contacts.find(item => item.id === (Number(params.get('id')) || 12)) ?? contacts[2]
  const [tab, setTab] = useState('TASKS'); const [editing, setEditing] = useState(false)
  return <div className="container-fluid py-3"><div className="d-flex align-items-center mb-3"><button className="btn btn-link" onClick={() => router.back()}><i className="bi bi-arrow-left" /></button><h1 className="h3 mb-0">{contact.name}</h1><button className="btn btn-primary ms-auto" onClick={() => setEditing(!editing)}><i className="bi bi-pencil me-1" />{editing ? 'SAVE' : 'EDIT'}</button></div><div className="row g-3">
    <section className="col-lg-5"><div className="card"><div className="card-body"><h2 className="h5">Details</h2><div className="row"><div className="col-5 text-center"><img src={contact.image} alt={contact.name} className="img-fluid rounded border object-fit-cover" width="184" height="184" /></div><div className="col-7"><p><Status value={contact.status} /></p><label className="form-label small">First Name</label><input className="form-control mb-2" readOnly={!editing} defaultValue={contact.name.split(' ')[0]} /><label className="form-label small">Last Name</label><input className="form-control" readOnly={!editing} defaultValue={contact.name.split(' ').slice(1).join(' ')} /></div></div><hr /><div className="row small"><div className="col-6"><b>Position</b><p>{contact.position}</p><b>Company</b><p>{contact.company}</p></div><div className="col-6"><b>Assigned to</b><p>{contact.assignedTo}</p></div></div><h3 className="h5">Contacts</h3><p>{contact.address}<br />{contact.city}, {contact.state} {contact.zip}<br />{contact.phone}<br />{contact.email}</p></div></div></section>
    <section className="col-lg-7"><div className="card"><div className="card-header"><ul className="nav nav-tabs card-header-tabs">{['TASKS', 'ACTIVITIES', 'OPPORTUNITIES'].map(item => <li className="nav-item" key={item}><button className={`nav-link ${tab === item ? 'active' : ''}`} onClick={() => setTab(item)}>{item}</button></li>)}</ul></div><div className="card-body">{tab === 'TASKS' ? <ul className="list-group list-group-flush">{['Call to clarify customer requirements', 'Send pictures/brochures', 'Follow up and discuss offer', 'Obtain CEO contact information', 'Generate a quote'].map(task => <li className="list-group-item" key={task}><input className="form-check-input me-3" type="checkbox" />{task}</li>)}</ul> : <div className="text-body-secondary py-5 text-center">{tab} for {contact.name}</div>}</div></div></section>
  </div></div>
}
