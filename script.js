const filters = document.querySelectorAll('[data-filter]');
const cards = document.querySelectorAll('.event-card');
const emptyState = document.querySelector('#emptyState');

function applyFilter(category) {
  let matches = 0;
  cards.forEach(card => {
    const show = category === 'All' || card.dataset.category === category;
    card.hidden = !show;
    if (show) matches++;
  });
  emptyState.hidden = matches !== 0;
  document.querySelectorAll('.filter').forEach(button => button.classList.toggle('active', button.dataset.filter === category));
  document.querySelector('#categories').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

filters.forEach(button => button.addEventListener('click', () => applyFilter(button.dataset.filter)));
document.querySelector('#searchForm')?.addEventListener('submit', event => {
  event.preventDefault();
  const query = document.querySelector('#searchInput').value.trim().toLowerCase();
  let matches = 0;
  cards.forEach(card => { const show = !query || card.textContent.toLowerCase().includes(query); card.hidden = !show; if(show) matches++; });
  emptyState.hidden = matches !== 0;
  document.querySelector('#categories').scrollIntoView({ behavior: 'smooth' });
});
document.querySelector('#menuButton')?.addEventListener('click', () => { document.querySelector('#mainNav').classList.toggle('open'); });

// Match the sample cards to real database events so checkout receives a valid event ID.
fetch('/api/events').then(response => response.ok ? response.json() : []).then(events => {
  if (!events.length) return;
  document.querySelectorAll('.event-card a[href="event.html"]').forEach((link, index) => {
    link.href = `event.html?id=${events[index % events.length].id}`;
  });
}).catch(() => {});
