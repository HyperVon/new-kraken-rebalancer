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
	// Subscribe returns a read-only channel that receives snapshots.
	Subscribe() <-chan model.PortfolioSnapshot
	Unsubscribe(ch <-chan model.PortfolioSnapshot)
}

// TradeHistoryServiceImpl implements TradeHistoryService.
type TradeHistoryServiceImpl struct {
	mu          sync.RWMutex
	repository  repository.TradeRepository
	history     []model.PortfolioSnapshot
	maxSize     int
	subscribers map[<-chan model.PortfolioSnapshot]chan model.PortfolioSnapshot
}

// NewTradeHistoryServiceImpl creates a new TradeHistoryServiceImpl.
func NewTradeHistoryServiceImpl(repo repository.TradeRepository) *TradeHistoryServiceImpl {
	return &TradeHistoryServiceImpl{
		repository:  repo,
		history:     make([]model.PortfolioSnapshot, 0),
		maxSize:     50,
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

	s.history = append([]model.PortfolioSnapshot{snapshot}, s.history...)
	if len(s.history) > s.maxSize {
		s.history = s.history[:s.maxSize]
	}

	// Copy while holding the lock; release before disk I/O.
	historyCopy := make([]model.PortfolioSnapshot, len(s.history))
	copy(historyCopy, s.history)
	s.mu.Unlock()

	if err := s.repository.Save(historyCopy); err != nil {
		return err
	}

	// Broadcast to subscribers with non-blocking writes.
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, ch := range s.subscribers {
		select {
		case ch <- snapshot:
		default:
			// Buffer full — drop for this subscriber to avoid blocking the rebalancer.
		}
	}
	return nil
}

func (s *TradeHistoryServiceImpl) GetHistory() []model.PortfolioSnapshot {
	s.mu.RLock()
	defer s.mu.RUnlock()

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

	ch := make(chan model.PortfolioSnapshot, 16)
	s.subscribers[ch] = ch
	return ch
}

func (s *TradeHistoryServiceImpl) Unsubscribe(ch <-chan model.PortfolioSnapshot) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if realCh, exists := s.subscribers[ch]; exists {
		close(realCh)
		delete(s.subscribers, ch)
	}
}
