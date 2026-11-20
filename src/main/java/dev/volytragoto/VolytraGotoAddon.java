package dev.volytragoto;

import dev.volytragoto.commands.VGotoCommand;
import dev.volytragoto.modules.VolytraGoto;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Modules;

/** Meteor addon entrypoint: the Volytra Goto module (Movement) and the .vgoto command. */
public class VolytraGotoAddon extends MeteorAddon {
    @Override
    public void onInitialize() {
        Modules.get().add(new VolytraGoto());
        Commands.add(new VGotoCommand());
    }

    @Override
    public String getPackage() {
        return "dev.volytragoto";
    }
}
