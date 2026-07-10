package com.panopticon.api;

import com.panopticon.model.PanelRuntimeState;
import com.panopticon.runtime.PanelRuntimeTracker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/runtime")
public class RuntimeController {

    private final PanelRuntimeTracker runtimeTracker;

    public RuntimeController(PanelRuntimeTracker runtimeTracker) {
        this.runtimeTracker = runtimeTracker;
    }

    @GetMapping("/panels")
    public List<PanelRuntimeState> panels() {
        return runtimeTracker.all();
    }
}
