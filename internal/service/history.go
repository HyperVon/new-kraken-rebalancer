package service

import (
	"sync"

	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/HyperVon/new-kraken-rebalancer/internal/repository"
)

// TradeHistoryService defines operations for history snapshot tracking and broadcasting.
type TradeHistoryService interface {
	Init() error
	AddSnapshot(snapshot model.PortfolioSnapshot) error
	GetHistory() []model.PortfolioSnapshot
	GetLatestSnapshot() (model.PortfolioSnapshot, bool)
	// Subscribe returns a read-only channel (<-chan) that receives snapshots.
	Subscribe() <-chan model.PortfolioSnapshot
	Unsubscribe(ch <-chan model.PortfolioSnapshot)
}

// TradeHistoryServiceImpl implements TradeHistoryService.
type TradeHistoryServiceImpl struct {
	// mu is a Read-Write Mutex. Multiple goroutines can hold a Read lock (RLock) simultaneously,
	// but only one goroutine can hold a Write lock (Lock), blocking all readers. This prevents data races.
	mu         sync.RWMutex
	repository repository.TradeRepository
	history    []model.PortfolioSnapshot
	maxSize    int
	// subscribers maps receive-only channel pointers (keys) to read-write channels (values)
	// to implement the observer/pub-sub pattern cleanly using Go channels.
	subscribers map[<-chan model.PortfolioSnapshot]chan model.PortfolioSnapshot
}

// NewTradeHistoryServiceImpl creates a new TradeHistoryServiceImpl.
func NewTradeHistoryServiceImpl(repo repository.TradeRepository) *TradeHistoryServiceImpl {
	return &TradeHistoryServiceImpl{
		repository: repo,
		history:    make([]model.PortfolioSnapshot, 0),
		maxSize:    50,
		// Map must be initialized before use, otherwise writing to it causes a panic.
		subscribers: make(map[<-chan model.PortfolioSnapshot]chan model.PortfolioSnapshot),
	}
}

func (s *TradeHistoryServiceImpl) Init() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	loaded, err := s.repository.Load()
	if err != nil {
		return err
	}
	s.history = loaded
	return nil
}

func (s *TradeHistoryServiceImpl) AddSnapshot(snapshot model.PortfolioSnapshot) error {
	s.mu.Lock()
	// Prepend snapshot (insert at index 0) by creating a new slice containing the single item
	// and unpacking/appending the rest of the slice elements to it.
	s.history = append([]model.PortfolioSnapshot{snapshot}, s.history...)
	if len(s.history) > s.maxSize {
		// Slice bounds slicing to truncate the slice size back to maxSize.
		s.history = s.history[:s.maxSize]
	}

	// We create a deep copy of the slice while holding the lock. This allows us to
	// release the lock before writing to the database file (which is a slow disk I/O operation).
	historyCopy := make([]model.PortfolioSnapshot, len(s.history))
	copy(historyCopy, s.history)
	s.mu.Unlock()

	if err := s.repository.Save(historyCopy); err != nil {
		return err
	}

	// Broadcast snapshot to all active subscribers.
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, ch := range s.subscribers {
		// Non-blocking write: select block tries to write to the channel.
		// If the channel's buffer is full (e.g. slow client), the default case matches immediately,
		// preventing the entire rebalancer execution loop from blocking/lagging.
		select {
		case ch <- snapshot:
		default:
			// If buffer is full, event is dropped for this specific client
		}
	}
	return nil
}

func (s *TradeHistoryServiceImpl) GetHistory() []model.PortfolioSnapshot {
	s.mu.RLock()
	defer s.mu.RUnlock()

	// Always copy slices before returning them from thread-safe resources,
	// otherwise external callers might read/write values concurrently, causing a data race.
	historyCopy := make([]model.PortfolioSnapshot, len(s.history))
	copy(historyCopy, s.history)
	return historyCopy
}

func (s *TradeHistoryServiceImpl) GetLatestSnapshot() (model.PortfolioSnapshot, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if len(s.history) == 0 {
		return model.PortfolioSnapshot{}, false
	}
	return s.history[0], true
}

func (s *TradeHistoryServiceImpl) Subscribe() <-chan model.PortfolioSnapshot {
	s.mu.Lock()
	defer s.mu.Unlock()

	// Initialize a buffered channel with a capacity of 16.
	// Buffered channels do not block the sender until the buffer is full.
	ch := make(chan model.PortfolioSnapshot, 16)
	s.subscribers[ch] = ch
	return ch
}

func (s *TradeHistoryServiceImpl) Unsubscribe(ch <-chan model.PortfolioSnapshot) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if realCh, exists := s.subscribers[ch]; exists {
		// close(ch) lets receivers know that no more events will be sent on this channel.
		close(realCh)
		delete(s.subscribers, ch)
	}
}
