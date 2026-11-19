package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HgramContentHeuristicsTest {

    @Test
    public void detectsExplicitAdvertisementMarker() {
        assertEquals(HgramContentHeuristics.Reason.ADVERTISEMENT,
                HgramContentHeuristics.classify("#реклама Подробности акции и условия размещены в публикации для наших читателей."));
    }

    @Test
    public void detectsPromotionalCallToActionWithLink() {
        assertEquals(HgramContentHeuristics.Reason.ADVERTISEMENT,
                HgramContentHeuristics.classify("Закажи со скидкой по ссылке https://example.com сегодня и получи подробную информацию о предложении."));
    }

    @Test
    public void detectsLinkedWebinarPromotion() {
        assertEquals(HgramContentHeuristics.Reason.ADVERTISEMENT,
                HgramContentHeuristics.classify("Сегодня в 14:00 трейдер из нашей команды проведет полезный вебинар. Подробности и ссылка для участия: https://example.com/webinar"));
    }

    @Test
    public void detectsProductSalesPost() {
        assertEquals(HgramContentHeuristics.Reason.ADVERTISEMENT,
                HgramContentHeuristics.classify("Заказывайте нашу продукцию только у официальных менеджеров и на сайте. Акции и скидки здесь: https://example.com/shop"));
    }

    @Test
    public void detectsGiftWithPurchasePromotion() {
        assertEquals(HgramContentHeuristics.Reason.ADVERTISEMENT,
                HgramContentHeuristics.classify("Время подарков: 1 флакон — 1 подарок к каждой позиции Morvex. Подробности на https://example.com/shop"));
    }

    @Test
    public void detectsKnownClickbaitPhrase() {
        assertEquals(HgramContentHeuristics.Reason.CLICKBAIT,
                HgramContentHeuristics.classify("Вы не поверите, что произошло на этой неделе. Подробный разбор событий и вся важная информация внутри."));
    }

    @Test
    public void leavesOrdinaryLongPostAlone() {
        assertEquals(HgramContentHeuristics.Reason.NONE,
                HgramContentHeuristics.classify("Городская комиссия представила доклад о развитии транспорта. В документ вошли новые маршруты и предложения жителей, собранные за последние месяцы."));
    }

    @Test
    public void financialNewsAndPlainLinkAreNotAutomaticallyClassifiedAsAds() {
        assertEquals(HgramContentHeuristics.Reason.NONE,
                HgramContentHeuristics.classify("Спрос на криптовалюту вырос после решения банка. Аналитики оценивают влияние на экономику и рынок: https://example.com/news"));
    }

    @Test
    public void detectsUnmarkedServicePromotionWithLinkAndCallToAction() {
        assertEquals(HgramContentHeuristics.Reason.ADVERTISEMENT,
                HgramContentHeuristics.classify("Запишись на процедуру в нашей студии. Посмотри услуги и выбери удобное время: https://example.com/book"));
    }
}
