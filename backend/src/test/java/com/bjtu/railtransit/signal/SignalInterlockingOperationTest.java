package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.signal.domain.SignalAspect;
import com.bjtu.railtransit.signal.model.Route;
import com.bjtu.railtransit.signal.service.LineProfileLoader;
import com.bjtu.railtransit.signal.service.SignalInterlockingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignalInterlockingOperationTest {

    @Test
    void buildingRealRouteOpensOnlyStartSignalAndCancelRestoresRed() {
        LineProfileLoader loader = new LineProfileLoader();
        SignalInterlockingService service = new SignalInterlockingService(loader);
        Route route = loader.getLineProfile().getRoutes().stream()
                .filter(item -> "XQ1-Z5".equals(item.getName()))
                .findFirst()
                .orElseThrow();

        service.setSignalAspect(String.valueOf(route.getStartSignalId()), SignalAspect.RED);
        service.setSignalAspect(String.valueOf(route.getEndSignalId()), SignalAspect.RED);
        service.buildRoute(route.getId());

        assertEquals(SignalAspect.GREEN,
                service.getAllSignalAspects().get(String.valueOf(route.getStartSignalId())));
        assertEquals(SignalAspect.RED,
                service.getAllSignalAspects().get(String.valueOf(route.getEndSignalId())));

        service.cancelRoute(route.getId());
        assertEquals(SignalAspect.RED,
                service.getAllSignalAspects().get(String.valueOf(route.getStartSignalId())));
    }
}
