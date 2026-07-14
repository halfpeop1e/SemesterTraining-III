package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.service.LineProfileLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LineProfileMileageDeterminismTest {

    @Test
    void cyclicTeacherTopologyUsesAStableArtificialOrigin() throws Exception {
        LineProfile first = new LineProfileLoader().loadFromClasspath("line-profile.json");
        LineProfile second = new LineProfileLoader().loadFromClasspath("line-profile.json");

        assertEquals(0.0, first.segStartM("1"), 0.001);
        assertEquals(first.segStartM("1"), second.segStartM("1"), 0.001);
        assertEquals(first.segStartM("13"), second.segStartM("13"), 0.001);
        assertEquals(first.segStartM("231"), second.segStartM("231"), 0.001);
    }
}
