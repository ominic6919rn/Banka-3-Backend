package rs.raf.stock_service.service;

import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import rs.raf.stock_service.client.BankClient;
import rs.raf.stock_service.client.UserClient;
import rs.raf.stock_service.domain.dto.ActuaryLimitDto;
import rs.raf.stock_service.domain.dto.CreateOrderDto;
import rs.raf.stock_service.domain.dto.OrderDto;
import rs.raf.stock_service.domain.dto.TaxDto;
import rs.raf.stock_service.domain.entity.Listing;
import rs.raf.stock_service.domain.entity.Order;
import rs.raf.stock_service.domain.entity.PortfolioEntry;
import rs.raf.stock_service.domain.entity.Transaction;
import rs.raf.stock_service.domain.enums.OrderDirection;
import rs.raf.stock_service.domain.enums.OrderStatus;
import rs.raf.stock_service.domain.enums.OrderType;
import rs.raf.stock_service.domain.enums.TaxStatus;
import rs.raf.stock_service.domain.mapper.ListingMapper;
import rs.raf.stock_service.domain.mapper.OrderMapper;
import rs.raf.stock_service.exceptions.CantApproveNonPendingOrder;
import rs.raf.stock_service.exceptions.ListingNotFoundException;
import rs.raf.stock_service.exceptions.OrderNotFoundException;
import rs.raf.stock_service.exceptions.PortfolioEntryNotFoundException;
import rs.raf.stock_service.repository.*;
import rs.raf.stock_service.utils.JwtTokenUtil;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Random;

@Service
@AllArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final JwtTokenUtil jwtTokenUtil;
    private final UserClient userClient;
    private final BankClient bankClient;
    private ListingRepository listingRepository;
    private ListingPriceHistoryRepository listingPriceHistoryRepository;
    private ListingMapper listingMapper;
    private TransactionRepository transactionRepository;
    private final PortfolioService portfolioService;
    private PortfolioEntryRepository portfolioEntryRepository;

    public Page<OrderDto> getOrdersByStatus(OrderStatus status, Pageable pageable) {
        Page<Order> ordersPage;

        if (status == null) {
            ordersPage = orderRepository.findAll(pageable);
        } else {
            ordersPage = orderRepository.findByStatus(status, pageable);
        }

        return ordersPage.map(order -> OrderMapper.toDto(order, listingMapper.toDto(order.getListing(),
                listingPriceHistoryRepository.findTopByListingOrderByDateDesc(order.getListing()))));
    }


    public void approveOrder(Long id, String authHeader) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        if (!order.getStatus().equals(OrderStatus.PENDING))
            throw new CantApproveNonPendingOrder(order.getId());

        Long userId = jwtTokenUtil.getUserIdFromAuthHeader(authHeader);

        order.setStatus(OrderStatus.APPROVED);
        order.setApprovedBy(userId);
        order.setLastModification(LocalDateTime.now());

        orderRepository.save(order);

        if(order.getOrderType() == OrderType.MARKET)
            executeOrder(order);
    }

    public void declineOrder(Long id, String authHeader) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        if (!order.getStatus().equals(OrderStatus.PENDING))
            throw new CantApproveNonPendingOrder(order.getId());

        order.setStatus(OrderStatus.DECLINED);
        order.setApprovedBy(jwtTokenUtil.getUserIdFromAuthHeader(authHeader));
        order.setLastModification(LocalDateTime.now());
        orderRepository.save(order);
    }

    public OrderDto createOrder(CreateOrderDto createOrderDto, String authHeader) {
        Long userId = jwtTokenUtil.getUserIdFromAuthHeader(authHeader);
        Listing listing = listingRepository.findById(createOrderDto.getListingId())
                .orElseThrow(() -> new ListingNotFoundException(createOrderDto.getListingId()));

        Order order = OrderMapper.toOrder(createOrderDto, userId, listing);

        if (jwtTokenUtil.getUserRoleFromAuthHeader(authHeader).equals("CLIENT")) {
            order.setStatus(verifyBalance(order) ? OrderStatus.APPROVED : OrderStatus.DECLINED);
        } else {
            ActuaryLimitDto actuaryLimitDto = userClient.getActuaryByEmployeeId(userId); // throw agentNotFound

            BigDecimal approxPrice = BigDecimal.valueOf(order.getContractSize()).multiply(order.getPricePerUnit().
                    multiply(BigDecimal.valueOf(order.getQuantity())));

            if (!actuaryLimitDto.isNeedsApproval() && actuaryLimitDto.getLimitAmount().subtract(actuaryLimitDto.
                    getUsedLimit()).compareTo(approxPrice) >= 0)
                    order.setStatus(OrderStatus.APPROVED);
        }

        if (order.getDirection().equals(OrderDirection.SELL)) {
            PortfolioEntry portfolioEntry = portfolioEntryRepository.findByUserIdAndListing(userId, listing).
                    orElseThrow(PortfolioEntryNotFoundException::new);
            BigDecimal buyingPrice = portfolioEntry.getAveragePrice().multiply(BigDecimal.valueOf(order.getQuantity()));
            BigDecimal sellPrice = order.getPricePerUnit().multiply(BigDecimal.valueOf(order.getQuantity()));
            BigDecimal potentialProfit = sellPrice.subtract(buyingPrice);
            if (potentialProfit.compareTo(BigDecimal.ZERO) > 0) {
                order.setTaxStatus(TaxStatus.PENDING);
                order.setTaxAmount(potentialProfit.multiply(new BigDecimal("0.15")));
            } else {
                order.setTaxStatus(TaxStatus.TAXFREE);
                order.setTaxAmount(BigDecimal.ZERO);
            }
        }else{
            order.setTaxStatus(TaxStatus.TAXFREE);
            order.setTaxAmount(BigDecimal.ZERO);
        }


        orderRepository.save(order);

        if (order.getOrderType() == OrderType.MARKET && order.getStatus() == OrderStatus.APPROVED)
            executeOrder(order);

        return OrderMapper.toDto(order, listingMapper.toDto(listing,
                listingPriceHistoryRepository.findTopByListingOrderByDateDesc(listing)));
    }

    private boolean verifyBalance(Order order) {
        if(order.getDirection() == OrderDirection.SELL) return true;

        BigDecimal price = BigDecimal.valueOf(order.getContractSize()).multiply(BigDecimal.valueOf(order.getQuantity()))
                .multiply(order.getPricePerUnit());

        BigDecimal commissionPercentage;
        BigDecimal commissionMax;
        if (order.getOrderType() == OrderType.MARKET || order.getOrderType() == OrderType.STOP){
            commissionPercentage = BigDecimal.valueOf(0.14);
            commissionMax = BigDecimal.valueOf(7);
        }
        else{
            commissionPercentage = BigDecimal.valueOf(0.24);
            commissionMax = BigDecimal.valueOf(12);
        }

        price = price.add(price.multiply(commissionPercentage).min(commissionMax));
        return price.compareTo(bankClient.getAccountBalance(order.getAccountNumber())) <= 0;
    }

    @Async
    public void executeOrder(Order order) {
        if (order.getIsDone() || order.getStatus() != OrderStatus.APPROVED) return; //better safe than sorry
        order.setStatus(OrderStatus.PROCESSING);

        if (order.isAllOrNone()){
            executeTransaction(order, order.getRemainingPortions());
        } else {
            Random random = new Random();

            while (order.getRemainingPortions() > 0) {
                executeTransaction(order, random.nextInt(1, order.getRemainingPortions() + 1));
                orderRepository.save(order);
            }
        }

        order.setIsDone(true);
        order.setStatus(OrderStatus.DONE);
        orderRepository.save(order);

        portfolioService.updateHoldingsOnOrderExecution(order);
    }

    private void executeTransaction(Order order, Integer batchSize){
        int extraTime = order.getAfterHours() ? 300000 : 0;
        Random random = new Random();

        try {
            Thread.sleep(random.nextInt(0, 24 * 60 / (order.getQuantity() /
                    order.getRemainingPortions())) + extraTime);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        BigDecimal totalPrice = BigDecimal.valueOf(batchSize).multiply(order.getPricePerUnit())
                .multiply(BigDecimal.valueOf(order.getContractSize()));

        Transaction transaction = new Transaction(batchSize, order.getPricePerUnit(), totalPrice, order);
        transactionRepository.save(transaction);

        order.getTransactions().add(transaction);
        order.setLastModification(LocalDateTime.now());
        order.setRemainingPortions(order.getRemainingPortions() - batchSize);
    }

    @Scheduled(fixedRate = 15000)
    public void checkOrders() {
        checkStopOrders();
        checkStopLimitOrders();
        checkLimitOrders();
    }

    private void checkStopOrders(){
        List<Order> orders = orderRepository.findByIsDoneAndStatusAndOrderType(false, OrderStatus.APPROVED, OrderType.STOP);

        for (Order order : orders) {
            checkStopOrder(order);
        }
    }

    private void checkStopLimitOrders(){
        List<Order> orders = orderRepository.findByIsDoneAndStatusAndOrderType(false, OrderStatus.APPROVED, OrderType.STOP_LIMIT);

        for (Order order : orders) {
            if (!order.isStopFulfilled()){
               checkStopOrder(order);
            } else {
               checkLimitOrder(order);
            }
        }
    }

    private void checkLimitOrders(){
        List<Order> orders = orderRepository.findByIsDoneAndStatusAndOrderType(false, OrderStatus.APPROVED, OrderType.LIMIT);

        for (Order order : orders) {
           checkLimitOrder(order);
        }
    }

    private void checkStopOrder(Order order){
        if (order.getDirection() == OrderDirection.BUY){
            BigDecimal askPrice = order.getListing().getAsk() == null ? order.getListing().getPrice() : order.getListing().getAsk();

            if (askPrice.compareTo(order.getStopPrice()) > 0){
                order.setStopFulfilled(true);

                if (order.getOrderType() == OrderType.STOP){
                    order.setPricePerUnit(askPrice);
                    executeOrder(order);
                }
            }
        } else if (order.getListing().getPrice().compareTo(order.getStopPrice()) < 0) {
            order.setStopFulfilled(true);

            if (order.getOrderType() == OrderType.STOP){
                order.setPricePerUnit(order.getListing().getPrice());
                executeOrder(order);
            }
        }
    }

    private void checkLimitOrder(Order order){
        if (order.getDirection() == OrderDirection.BUY){
            BigDecimal askPrice = order.getListing().getAsk() == null ? order.getListing().getPrice() : order.getListing().getAsk();

            if(askPrice.compareTo(order.getPricePerUnit()) <= 0) {
                order.setPricePerUnit(order.getPricePerUnit().min(askPrice));
                executeOrder(order);
            }
        } else if (order.getListing().getPrice().compareTo(order.getPricePerUnit()) >= 0) {
            order.setPricePerUnit(order.getPricePerUnit().max(order.getListing().getPrice()));
            executeOrder(order);
        }
    }


    //@Scheduled(cron = "0 0 0 * * *")
    public void processTaxes() {
        for (Order order : orderRepository.findAll()) {
            if (order.getTaxAmount() != null && order.getTaxStatus().equals(TaxStatus.PENDING) &&
                    bankClient.getAccountBalance(order.getAccountNumber()).compareTo(order.getTaxAmount()) > 0) {
                TaxDto taxDto = new TaxDto();
                taxDto.setAmount(order.getTaxAmount());
                taxDto.setClientId(order.getUserId());
                taxDto.setSenderAccountNumber(order.getAccountNumber());
                bankClient.handleTax(taxDto);
                order.setTaxStatus(TaxStatus.PAID);
                orderRepository.save(order);
            }
        }
    }
}
