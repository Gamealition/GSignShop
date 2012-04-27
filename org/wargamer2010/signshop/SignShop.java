package org.wargamer2010.signshop;

import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.HashMap;
import java.io.IOException;
import java.io.File;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.*;
import java.util.Date;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.wargamer2010.signshop.listeners.*;
import org.wargamer2010.signshop.hooks.HookManager;

public class SignShop extends JavaPlugin{
    private final SignShopPlayerListener playerListener = new SignShopPlayerListener(this);
    private final SignShopBlockListener blockListener = new SignShopBlockListener();

    private static final Logger logger = Logger.getLogger("Minecraft");
    private static final Logger transactionlogger = Logger.getLogger("SignShop_Transactions");

    //Configurables
    private FileConfiguration config;    
    private int MaxSellDistance = 0;
    private static int MaxShopsPerPerson = 0;
    private static Boolean TransactionLog = false;
    private static boolean OPOverride = true;
    private static boolean AllowUnsafeEnchantments = false;
    private boolean AllowVariableAmounts = false;
    private boolean AllowEnchantedRepair = true;
    private boolean DisableEssentialsSigns = true;    

    //Statics
    public static Storage Storage;
    public static HashMap<String,List> Operations;
    public static HashMap<String,HashMap<String,String>> Messages;
    public static HashMap<String,String> Errors;
    public static HashMap<String,HashMap<String,Float>> PriceMultipliers;
    public static HashMap<String,List> Commands;
    public static HashMap<String,Integer> ShopLimits;
    
    //Permissions
    public static boolean USE_PERMISSIONS = false;    
    
    // Vault
    private Vault vault = null;
    
    private HashMap<String,Integer> validSignOperations;

    //Logging
    public void log(String message, Level level,int tag) {
        if(!message.equals(""))
            logger.log(level,("[SignShop] ["+tag+"] " + message));
    }
    public static void log(String message, Level level) {
        if(!message.equals(""))
            logger.log(level,("[SignShop] " + message));
    }
    public static void logTransaction(String customer, String owner, String Operation, String items, String Price) {
        if(SignShop.TransactionLog && !items.equals("")) {
            String message = ("Customer: " + customer + ", Owner: " + owner + ", Operation: " + Operation + ", Items: " + items + ", Price: " + Price);
            transactionlogger.log(Level.FINER, message);
        }
    }
            
    private void checkOldDir() {
        File olddir = new File("plugins", "SignShops");
        if(olddir.exists()) {
            if(!this.getDataFolder().exists()) {                
                boolean renamed = olddir.renameTo(this.getDataFolder());
                if(renamed)
                    log("Old configuration directory (SignShops) found and succesfully migrated.", Level.INFO);
                else
                    log("Old configuration directory (SignShops) found, but could not rename to SignShop. Please move configs manually!", Level.INFO);
            } else
                log("Old configuration directory (SignShops) found, but new (SignShop) exists. Please move configs manually!", Level.INFO);
        }
    }
    
    
    @Override
    public void onEnable() {
        // Migrate configs from old directory
        this.checkOldDir();        
        if(!this.getDataFolder().exists()) {
            this.getDataFolder().mkdir();
        }
        initConfig();        
        SignShop.Messages = configUtil.fetchHasmapInHashmap("messages", config);
        SignShop.Errors = configUtil.fetchStringStringHashMap("errors", config);
        SignShop.PriceMultipliers = configUtil.fetchFloatHasmapInHashmap("pricemultipliers", config);
        SignShop.Commands = configUtil.fetchListInHashmap("commands", config);
        SignShop.ShopLimits = configUtil.fetchStringIntegerHashMap("limits", config);
        
        //Create a storage locker for shops        
        SignShop.Storage = new Storage(new File(this.getDataFolder(),"sellers.yml"),this);
        SignShop.Storage.Save();
        
        try {
            FileHandler fh = new FileHandler("plugins/SignShop/Transaction.log", true);
            TransferFormatter formatter = new TransferFormatter();
            fh.setFormatter(formatter);
            fh.setLevel(Level.FINEST);
            transactionlogger.addHandler(fh);
            transactionlogger.setLevel(Level.FINEST);
            logger.setUseParentHandlers(false);
        } catch(IOException ex) {
            log("Failed to create transaction log", Level.INFO);
        }
        
        //Setup sign types
        initValidOps();
        SignShop.Operations = new HashMap<String,List>();
        
        HashMap<String,String> tempSignOperations = configUtil.fetchStringStringHashMap("signs", config);

        List tempSignOperationString = new ArrayList();
        List tempSignOperationInt;
        for(String sKey : tempSignOperations.keySet()){
            tempSignOperationString = Arrays.asList(tempSignOperations.get(sKey).split("\\,"));
            tempSignOperationInt = new ArrayList();

            for(int i=0;i<tempSignOperationString.size();i++){
                if(validSignOperations.containsKey((String) tempSignOperationString.get(i))){
                    tempSignOperationInt.add(validSignOperations.get((String) tempSignOperationString.get(i)));
                }
            }

            if(tempSignOperationString.contains("takePlayerItems")
            || tempSignOperationString.contains("givePlayerItems")
            || tempSignOperationString.contains("takeShopItems")
            || tempSignOperationString.contains("giveShopItems")
            || tempSignOperationString.contains("givePlayerRandomItem")){

                tempSignOperationInt.add(validSignOperations.get("usesChest"));

            }else if(tempSignOperationString.contains("setRedstoneOn")
            || tempSignOperationString.contains("setRedstoneOff")
            || tempSignOperationString.contains("setRedStoneOnTemp")
            || tempSignOperationString.contains("toggleRedstone")){

                tempSignOperationInt.add(validSignOperations.get("usesLever"));

            }
            if(tempSignOperationString.contains("takePlayerItems")
                    || tempSignOperationString.contains("givePlayerItems")
                    || tempSignOperationString.contains("takeShopItems")
                    || tempSignOperationString.contains("givePlayerRandomItem"))
                    tempSignOperationInt.add(validSignOperations.get("usesChestItems"));
                    

            SignShop.Operations.put(sKey, tempSignOperationInt);
        }
        
        setupVault();
        setupHooks();
        
        PluginDescriptionFile pdfFile = this.getDescription();
        PluginManager pm = getServer().getPluginManager();
        if(Vault.vaultFound) {
            // Register events
            pm.registerEvents(playerListener, this);
            pm.registerEvents(blockListener, this);
            if(DisableEssentialsSigns) {
                SignShopServerListener SListener = new SignShopServerListener(getServer());
                pm.registerEvents(SListener, this);
            }
            log("v" + pdfFile.getVersion() + " Enabled", Level.INFO);
        } else {
            SignShopLoginListener login = new SignShopLoginListener(this);
            pm.registerEvents(login, this);
            log("v" + pdfFile.getVersion() + " Disabled", Level.INFO);
        }
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String args[]) {
        String commandName = cmd.getName().toLowerCase();        
        if(!commandName.equalsIgnoreCase("signshop"))
            return true;
        if(args.length != 1)
            return false;
        if((sender instanceof Player) && !((Player)sender).isOp()) {
            ((Player)sender).sendMessage(ChatColor.RED + "You are not allowed to use that command. OP only.");
            return true;
        }
        if(args[0].equals("reload")) {
            Bukkit.getServer().getPluginManager().disablePlugin(this);
            Bukkit.getServer().getPluginManager().enablePlugin(this);
        } else
            return false;
        SignShop.log("Reloaded", Level.INFO);
        if((sender instanceof Player))
            ((Player)sender).sendMessage(ChatColor.GREEN + "SignShop has been reloaded");
        return true;
    }
    
    public void initConfig() {
        this.reloadConfig();
        File configFile = new File("plugins/SignShop", "config.yml");
        if(!configFile.exists()) {
            this.saveDefaultConfig();
            this.saveConfig();
        }
        config = this.getConfig();
        config.options().copyDefaults(true);
        this.saveConfig();
        this.reloadConfig();
        MaxSellDistance = config.getInt("MaxSellDistance", MaxSellDistance);
        TransactionLog = config.getBoolean("TransactionLog", TransactionLog);
        MaxShopsPerPerson = config.getInt("MaxShopsPerPerson", MaxShopsPerPerson);
        OPOverride = config.getBoolean("OPOverride", OPOverride);
        AllowVariableAmounts = config.getBoolean("AllowVariableAmounts", AllowVariableAmounts);        
        AllowEnchantedRepair = config.getBoolean("AllowEnchantedRepair", AllowEnchantedRepair);
        DisableEssentialsSigns = config.getBoolean("DisableEssentialsSigns", DisableEssentialsSigns);
        AllowUnsafeEnchantments = config.getBoolean("AllowUnsafeEnchantments", AllowUnsafeEnchantments);        
    }
    
    public void initValidOps() {
        validSignOperations = new HashMap<String,Integer>();

        validSignOperations.put("takePlayerMoney",1);
        validSignOperations.put("givePlayerMoney",2);
        validSignOperations.put("takePlayerItems",3);
        validSignOperations.put("givePlayerItems",4);
        validSignOperations.put("takeOwnerMoney",5);
        validSignOperations.put("giveOwnerMoney",6);
        validSignOperations.put("takeShopItems",7);
        validSignOperations.put("giveShopItems",8);
        validSignOperations.put("givePlayerRandomItem",10);
        validSignOperations.put("playerIsOp",11);
        validSignOperations.put("setDayTime",12);
        validSignOperations.put("setNightTime",13);
        validSignOperations.put("setRaining",14);
        validSignOperations.put("setClearSkies",16);
        validSignOperations.put("setRedstoneOn",17);
        validSignOperations.put("setRedstoneOff",18);
        validSignOperations.put("setRedStoneOnTemp",19);
        validSignOperations.put("toggleRedstone",20);
        validSignOperations.put("usesChest",21);
        validSignOperations.put("usesLever",22);
        validSignOperations.put("healPlayer",23);
        validSignOperations.put("repairPlayerHeldItem",24);
        validSignOperations.put("takeItemInHand",25);
        validSignOperations.put("usesChestItems",26);
    }
    
    public String getLogPrefix() {
        PluginDescriptionFile pdfFile = this.getDescription();
        String prefix = "[SignShop] [" +pdfFile.getVersion() +"]";
        return prefix;
    }
    
    private void closeHandlers() {
        Handler[] handlers = transactionlogger.getHandlers();
        for(int i = 0; i < handlers.length; i++)
            handlers[i].close();
    }
    
    @Override
    public void onDisable(){
        SignShop.Storage.Save();
        closeHandlers();
        
        log("Disabled", Level.INFO);
    }

    private void setupVault() {
        vault = new Vault(getServer());
        vault.setupChat();
        Boolean vault_Perms = vault.setupPermissions();
        if(!vault_Perms || Vault.permission.getName().equals("SuperPerms")) {
            log("Vault's permissions not found, defaulting to OP.", Level.INFO);
            USE_PERMISSIONS = false;
        } else
            USE_PERMISSIONS = true;
        Boolean vault_Economy = vault.setupEconomy();
        if(!vault_Economy)
            log("Could not hook into Vault's Economy!", Level.WARNING);
    }
    
    private void setupHooks() {
        HookManager.addHook("LWC");
        HookManager.addHook("Lockette");
        HookManager.addHook("WorldGuard");
    }
        
    public int getMaxSellDistance() {
        return MaxSellDistance;
    }
    
    public static int getMaxShopsPerPerson() {
        return MaxShopsPerPerson;
    }
    
    public static Boolean getOPOverride() {
        return OPOverride;
    }
    
    public Boolean getAllowVariableAmounts() {
        return AllowVariableAmounts;
    }
    
    public Boolean getAllowEnchantedRepair() {
        return AllowEnchantedRepair;
    }
    
    public static Boolean getAllowUnsafeEnchantments() {
        return AllowUnsafeEnchantments;
    }
    
    public class TransferFormatter extends Formatter {    
        private final DateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS");
        
        @Override
        public String format(LogRecord record) {
            StringBuilder builder = new StringBuilder(1000);
            builder.append(df.format(new Date(record.getMillis()))).append(" - ");            
            builder.append("[").append(record.getLevel()).append("] - ");
            builder.append(formatMessage(record));
            builder.append("\n");
            return builder.toString();
        }

        @Override
        public String getHead(Handler h) {
            return super.getHead(h);
        }

        @Override
        public String getTail(Handler h) {
            return super.getTail(h);
        }
    }
}