# Silicon Labs — "Build your own lightsaber (sounds)!" (Parts 1-5)

## Provenance

| Field | Value |
|---|---|
| Series | "Build your own lightsaber (sounds)!" — 5 parts, all retrieved |
| Author | **lynchtron** (Brad Fricker, Silicon Labs). Legacy t5 posts credit `lynchtron`, last edited by `llooper`. Migrated Experience Cloud posts render the author as the community handle `D720741534` (the migrated profile of the same author) |
| Label | *The Maker's Guide* |
| Published | 2016-11-18 (Part 2), 2016-11-29 (Part 3); Parts 1, 4, 5 undated on the migrated page but "10 years ago" as of 2026 (i.e. Nov 2016) |
| Retrieved | 2026-10-01 |
| How retrieved | Parts 1, 2, 3, 5: **live** from community.silabs.com. Part 4: **live**, but only reachable with the `_escaped_fragment_` query parameter on the canonical slug URL (the record now redirects to the community home page without it) |

### Retrieval notes (how the JavaScript-rendered pages were defeated)

- The site is a Salesforce Experience Cloud single-page app (`aura.app=markup://siteforce:communityApp`). A plain fetch returns ~458 KB of Aura bootstrap and **zero** article text.
- Appending `&_escaped_fragment_=` makes Salesforce server-side prerender the Knowledge/news record and return the full article body as ordinary HTML. Requested with a Googlebot User-Agent.
- Document IDs (Salesforce Knowledge share records), found by a Wayback CDX domain crawl of `community.silabs.com`:
  - Part 1 `a5U1M000000knw7UAA` (also seen: `a5U1M000000kntkUAA`)
  - Part 2 `a5U1M000000knsxUAA`
  - Part 3 `a5U1M000000knwzUAA`
  - Part 4 `a5U1M000000knjvUAA`
  - Part 5 `a5U1M000000knzNUAQ`
- Legacy t5 originals (archived, reachable only for anonymous read on Part 2/3 via Wayback):
  - `.../Build-your-own-lightsaber-sounds-Part-1/ba-p/182419`
  - `.../Build-your-own-lightsaber-sounds-Part-2/ba-p/183109`
  - `.../Build-your-own-lightsaber-sounds-Part-3/ba-p/184000`
- Companion source code: the blog links `github.com/SiliconLabs/makersguide`, which now **404s**. The live mirror is **`github.com/bfricker/makersguide`**, directory **`14_lightsaber_sounds/`** (source files `src/sound_effects_player.c`, `src/lightsaber_effects_player.c`, `src/main_tone_generator.c`, `src/dac_helpers.c/.h`, `src/adxl.c/.h`, `Drivers/microsd.c`, `FatFS/ff.c`). Constants quoted below as "repo" come from that file, not from the article prose.
- Part 6+ : **could not retrieve.** A domain-wide Wayback crawl of `community.silabs.com` returns exactly nine lightsaber URLs — Parts 1-5 in the new `/s/share/` scheme and Parts 1-3 in the legacy `/t5/` scheme. No Part 6 or later exists on the site or in the archive. The Wayback CDX query for `...Part-4/ba-p/*` returns empty, i.e. the legacy blog stopped at Part 3 and the migrated Experience Cloud blog stops at Part 5.

### Faithfulness markers

- Everything in the "Verbatim transcription" blocks below is the **real page text**, with only two mechanical edits: HTML tags stripped to newlines, and image alt-text removed. Where an equation or circuit diagram was an *image* on the page, the sentence around it is transcribed and the image content is called out explicitly — the image itself is **[reconstructed — not verbatim]** or, where nothing in the text pins it down, **could not retrieve**.
- `[reconstructed — not verbatim]` marks anything I derived rather than read (e.g. a formula whose image I could not see, filled in from the surrounding prose).
- **could not retrieve** marks a specific detail that genuinely is not in any source I could reach. Nothing else is inferred.

### Things worth flagging before you rely on any number

Two statements in the articles are internally inconsistent and are transcribed **exactly as published**, without correction:

1. Part 3: "it can drive to any of 212 values in-between those two voltages, or 4096 steps" — "212" is a mangled superscript 2^12. **[reconstructed — not verbatim]** the intended value is 2^12 = 4096 steps.
2. Part 3: "The gain ... is set by choosing 150 kOhm resistors ... for a gain of two" followed by "by adding two new 150 kOhm resistors on top of the surface-mounted 0603 resistors, you will drop the resistance to 75 kOhm for a gain of four" and "I have successfully run it at a gain of six by using 50 kOhm resistors". The gain formula is an image and is **could not retrieve**; the prose is transcribed as-is.

---


## Part 1 — theory, materials, and the GPIO/DAC/Class-D roadmap

URL: https://community.silabs.com/s/share/a5U1M000000knw7UAA/build-your-own-lightsaber-sounds-part-1 — retrieved **live** 2026-10-01 via `&_escaped_fragment_=` prerender.

### Verbatim transcription


```
Technical Blog
Build your own lightsaber (sounds)! - Part 1
10 years ago
by
D720741534
In the last chapter, we learned all about how to create sound using an external I2S chip and digital sound files.  In this chapter, we will get a little bit more in depth with sound and generate simple tones with a GPIO pin.  Then, we will use the integrated 12-bit DAC within the Wonder Gecko to produce sound with a more analog nature and run that through an external audio amplifier.  We will blend together sounds from multiple lightsaber sound effects and trigger those sounds with the accelerometer from chapter 10, resulting in our very own lightsaber sound effects generator.
Sound effects for consumer gadgets can be more forgiving than high-end musical audio.  We can get by on just a bare GPIO pin if we are careful about the type of sounds that we try to reproduce.  For some gadgets, perhaps a few beeps, clicks, and scratchy noises will do the trick.  As we move to voice tracks that have a wider range of audio frequencies, more fidelity is needed.  The 12-bit DAC on the Wonder Gecko (also available in most EFM32 models, check the datasheet) can be used to generate sound effects, voices, and lower-quality music.
The EFM32 can drive up to 20 mA on a single GPIO pinThis is probably not going to do the trick for the volume that we need on a small speaker.  If we tried to drive a speaker directly from a GPIO pin, it could damage the GPIO or just result in low volume audio.  In order to boost the volume, we need to amplify it in either voltage swing, current capacity, or both, to produce the desired power and sound levels for the situation.
Since we have already learned how to use an accelerometer with I2C in chapter 10, we can dust that off now and use it to detect motion that triggers lightsaber sounds that are stored on the MicroSD card that we used in the last chapter.   We will drive all of that out of the DAC on the EFM32 and then on to a TI differential mono amplifier.  But before go there, we will take a step back just a bit and learn about how to make the most rudimentary sound with nothing but a GPIO pi and, a speaker.
The source code for all examples in this book can be found on github,
here
.
Materials Required for this Chapter:
ADXL345 Breakout Board
Micro SD Breakout Board
TI TPA2005D1 Breakout Board
Micro SD Memory Card Breakout Board
Micro SD Memory Card and SD Card Adapter
Sound and Amplifier Theory
Audio theory will melt your brain.  When you go looking for information on how to build audio circuits for your electronic gadgets, you will quickly run into discussions of physics, calculus, and all sorts of horrendous-looking mathematical expressions.
But all I want to do is play some sound effects!
I hear you.  I ran into this problem when I first tried to build my first audio circuit.  I still have a lot to learn, but I will share with you some simple things that I have learned that should give you a good foundation to learn more.
In the old days when we played music on a record player or a cassette tape in a tape player, the audio information was stored and retrieved in a completely analog format.  The resulting audio signal consisted of sine waves and were fed into an analog amplifier,  then routed through a speaker and turned into sound, which we can hear.
Today, most of our music that we enjoy is stored or transmitted as a digital representation of an analog signal.  So, digital is the starting point for your gadget sound effects.  In order to turn that digital information into an analog signal suitable for amplification, a Digital-to-Analog Converter (DAC) is used, such as the I2S DAC that we learned about in the last chapter.  A DAC is capable of outputting an analog voltage, that is, a voltage that is not a binary 0 or 1, but some small division of voltage in between.  Any discussion of DAC’s then requires the explanation of sample rates and bit counts and the minimum number of those to accurately reproduce the audio for our ears.
On the one end of the spectrum, we have the old-fashioned and warm tones of the old analog audio format, and on the other end, we have the precisely calculated, perfect-in-every-way digital recordings of the modern era.  Yet, there is middle ground!  By using nothing more than a GPIO pin and purely digital signaling, we can produce analog sound.  The reason why this works is because of filtering.  The digital pulses that we will use to generate audio from a GPIO are fast-switching square waves, and the impedance of the audio speaker coil and magnet is an inductor, which is a low-pass filter.  It simply won’t allow the high-frequencies of the digital signal to make it through the speaker coil.  The impedance of a typical 8Ω speaker limits the switching frequency, so that what is left on the speaker cone is an average voltage over time.  If we can alter the average voltage value many times a second, we have a cheap DAC.
Since we have to control the GPIO many times per second, we will use the EFM32 timer circuits connected to a GPIO in Pulse Width Modulation (PWM) mode to make the quick changes necessary from instant to instant in order to reproduce a sinusoidal waveform.  The rule of thumb is to run a sample rate that is 10 times as fast as the frequency of sound that we want to create and a PWM switching frequency is that is 10 times as fast as the sample rate.  So if we want to create a 4 kHz tone for example, then we should use a 40 kHz sample rate and a 400 kHz PWM switching frequency.  We will vary the PWM duty cycle every audio sample, which will then run for 10 PWM clocks, before the PWM duty cycle is changed for the next sample.
Once we have created an analog signal from the digital audio data, we need to amplify it if we want to be able to hear it loud and clear.  This is another area of electronics where your research can cause your head to explode.  There is an enormous amount of information and opinions on the Internet, and it can get you sidetracked from finding your way to a solution.  There are many ways to amplify a signal.  The type of amplification that we will be learning about in this chapter is known as Class D, but also goes by the name of PWM or simply digital amplification.
Class D amplifiers use the same PWM principle that we have just learned about, but are connected to more powerful current and voltage sources than the source audio device.  They are up to 95% efficient because at times where there is no audio data, the PWM duty cycle is 50%, and the output signal stays quiescent on both inputs to the speaker.  If there is no change in output voltage to either pin of the speaker, then there is no current consumed by the speaker.  Class D amps are more efficient than Class A, B, or AB, which are all more analog in nature and can waste power when there is no audio data to amplify.  Later in the chapter, we will use TI’s TPA2005D1 Class D differential mono amplifier.
In the next section, we will create a simple audio tone on a GPIO pin, examine that waveform on an oscilloscope.
```

## Part 2 — generating a tone with a timer PWM output (no DAC)

URL: https://community.silabs.com/s/share/a5U1M000000knsxUAA/build-your-own-lightsaber-sounds-part-2 — retrieved **live** 2026-10-01 via `&_escaped_fragment_=` prerender.

### Verbatim transcription


```
Technical Blog
Build your own lightsaber (sounds)! - Part 2
10 years ago
by
D720741534
In the last section, we learned more about digital audio and Digital-to-Audio Converters (DACs) and different types of amplification.  In this section, we will take a step back to the fundamentals of digital audio and use the Pulse Width Modulation (PWM) mode of a timer peripheral output to a GPIO pin to create a tone.
Create an Audio Tone with a PWM Timer
For the first experiment with digitally-produced sound, we will create simple tones only, through the use of nothing but a digitial PWM output.  We will construct a program that will output any frequency under 24 kHz.  The program is pretty short, so I will provide the complete source here and then discuss it below the source code.
#include "em_device.h"
#include "em_chip.h"
#include "em_cmu.h"
#include "em_timer.h"
#include "em_gpio.h"
#define PWM_TIMER_CHANNEL           2
#define PWM_TIMER                   TIMER3
#define SAMPLE_TIMER                TIMER1
#define SAMPLE_TIMER_INT            TIMER1_IRQn
#define TIMER_PRESCALE              timerPrescale2
#define TONE_FREQ                   3500
// Storage for the max frequency available to this program
uint32_t max_frequency;
uint32_t top_value(uint32_t frequency)
{
if (frequency == 0) return 0;
return (100*max_frequency/frequency);
}
// This is the timer that controls the PWM rate and PWM value per sample
// This timer is routed to a GPIO and needs no interrupt to set the PWM value
// when the compare register is reached in the timer count
void setup_pwm_timer3()
{
// Create the timer count control object initializer
TIMER_InitCC_TypeDef timerCCInit = TIMER_INITCC_DEFAULT;
timerCCInit.mode = timerCCModePWM;
timerCCInit.cmoa = timerOutputActionClear;
timerCCInit.cofoa = timerOutputActionSet;
// Configure Compare Channel 2
TIMER_InitCC(PWM_TIMER, PWM_TIMER_CHANNEL, &timerCCInit);
// Route CC2 to location 1 (PE3) and enable pin for cc2
PWM_TIMER->ROUTE |= (TIMER_ROUTE_CC2PEN | TIMER_ROUTE_LOCATION_LOC1);
// Create a timerInit object, and set the freq to maximum
TIMER_Init_TypeDef timerInit = TIMER_INIT_DEFAULT;
timerInit.prescale = TIMER_PRESCALE;
// Set Top Value
TIMER_TopSet(PWM_TIMER, top_value(TONE_FREQ) / 10);
TIMER_Init(PWM_TIMER, &timerInit);
}
// This is the timer that used to know when to fetch a sample to process
// This timer is only used to generate interrupts
void setup_sample_rate_timer1()
{
// Create a timerInit object, and set the freq to maximum
TIMER_Init_TypeDef timerInit = TIMER_INIT_DEFAULT;
timerInit.prescale = TIMER_PRESCALE;
// Set Top Value
TIMER_TopSet(SAMPLE_TIMER, top_value(TONE_FREQ) );
TIMER_Init(SAMPLE_TIMER, &timerInit);
TIMER_IntEnable(SAMPLE_TIMER, TIMER_IF_OF);
// Enable TIMER0 interrupt vector in NVIC
NVIC_EnableIRQ(SAMPLE_TIMER_INT);
}
uint32_t sine_wave_generator()
{
const uint8_t lookup_table[10] = {5,8,10,10,8,5,2,0,0,2};
static uint8_t count = 0;
// Lookup the value
uint32_t result = lookup_table[count];
// Adjust count for next time and correct for overflow
count++; if (count >= 10) count = 0;
return result * top_value(TONE_FREQ) / 100;
}
int main(void)
{
CHIP_Init();
CMU_ClockEnable(cmuClock_GPIO, true);
CMU_ClockEnable(cmuClock_TIMER1, true);
CMU_ClockEnable(cmuClock_TIMER3, true);
// Need to boost the clock to get above 7kHz
CMU_ClockSelectSet(cmuClock_HF, cmuSelect_HFXO);
// Calculate the max frequency supported by this program
uint32_t timer_freq = CMU_ClockFreqGet(cmuClock_TIMER3);
max_frequency = (timer_freq / (1 << TIMER_PRESCALE)) / 1000;
// Set up our timers that do almost all the work
setup_sample_rate_timer1();
setup_pwm_timer3();
// Enable GPIO output for Timer3, Compare Channel 2 on PE2
GPIO_PinModeSet(gpioPortE, 2, gpioModePushPull, 0);
// Show the sample rate on PE1 for debug
GPIO_PinModeSet(gpioPortE, 1, gpioModePushPull, 0);
while (1)
{
}
}
void TIMER1_IRQHandler(void)
{
// Clear the interrupt
TIMER_IntClear(SAMPLE_TIMER, TIMER_IF_OF);
// This is for debug viewing on a scope
GPIO_PinOutToggle(gpioPortE, 1);
// Get the next sine value
uint32_t pwm_duty = sine_wave_generator();
// Set the new duty cycle for this sample based on the generator
TIMER_CompareBufSet(PWM_TIMER, PWM_TIMER_CHANNEL, pwm_duty);
}
First, we initialize clocks and GPIOs in the main part of the program, then call upon two different timers, TIMER1 defined as SAMPLE_TIMER for the sample rate and TIMER3 defined as PWM_TIMER for the PWM function.
The sample timer is configured to trigger an interrupt every time that a new sample is required.  This sets the overall cadence of the tone generator.  When the timer expires, the TIMER1 interrupt handler springs into action, fetching the next value of a sine wave, then placing this value into the PWM compare registers through the TIMER_CompareBufSet function.
Audible sound is located in a range from around 50 Hz to 20,000 Hz, so I needed a frequency that would allow my sample rate timer to run up to 200,000 times a second and my PWM timer at 2,000,000 times a second.  At first, I tried to run everything with the HFRCO clock source, which only allowed my top frequency to get to 7 kHz.  So I switched to using HFXO as the clock source, which gave a 48 MHz HFPER clock.  I then used a timer divisor of 2 to give me 24 MHz PWM clock.  I used a spreadsheet to make all of these things easier to figure out.  In order to get a PWM timer that was 10x the sample rate timer, I set my sample rate TOP register to 100 and PWM timer TOP register to 10.  This resulted in a 240 kHz sample rate, which creates a maximum frequency that is able to be generated by this code to be 24 kHz, which is stored in the max_frequency global variable.  This is a higher sample rate than what was required for 20 kHz audio.  It is calculated in the code by finding the timer frequency, dividing that by the timer clock divisor, and then dividing that result by 1000, which takes into account the two TOP values, i.e. 100 x 10 = 1000.
Here is another way of looking at the same algorithm a different way: Since the sample rate timer is running at 24 MHz, setting SAMPLE_TIMER ->TOP to overflow every 100 ticks creates a maximum sample rate of 24 Mhz/100 = 240 kHz.  For the PWM_TIMER, we want that to reset its cycle at 10x the rate of the sample rate of 240 kHz.  The PWM_TIMER and the SAMPLE_TIMER are both running at 24 MHz, but the sample timer is triggered 240,000 timers a second and the PWM_TIMER is set to overflow by setting its TOP value to 1/10th of the sample rate timer, which is 24,000 times a second for a 24 kHz sound tone.
The sine_wave_generator function simply returns a value between 0 and 10 and every time it is called, incrementing the static count variable, and then scales the return value so that it fits within PWM_TIMER->TOP register range.  The values in the lookup_table array were generated with an online tool
here
that takes the number of points and range of values to construct one period of a sine wave.  You don’t need a lot of data points when the sound data is a simple tone.
In order to test my implementation, I constructed a test circuit like so:
The low-pass RC filter in this circuit smoothed out the digital PWM pulses and allowed the frequency to be studied.  The cutoff frequency of the RC filter is
where I have chosen R = 10 kΩ.  Rearranging, and setting Fcutoff to 7 kHz, we can find the value of C:
Where C is found to be around 2 nF, or 0.002 µF.  I placed the resistor and capacitor on the output of my PE2 pin and recorded the following waveforms.
As you can see, the purely digital PWM waveforms are being translated into nice sinusoidal waveforms after the RC filter.  The sample rate indicator on channel 2 shows when a new PWM value is loaded into the PWM compare buffer registers whenever channel 2 changes state.
Therefore, you don't always need a DAC to create audio.  The key to getting the tone we wanted, in this case, is external analog filtering.
In the next section, we will connect the MicroSD card to the starter kit, introduce the internal EFM32 DAC peripheral and cover single-ended versus differential mode output.
```

## Part 3 — MicroSD, the onboard 12-bit differential DAC, and the TI TPA2005D1

URL: https://community.silabs.com/s/share/a5U1M000000knwzUAA/build-your-own-lightsaber-sounds-part-3 — retrieved **live** 2026-10-01 via `&_escaped_fragment_=` prerender.

### Verbatim transcription


```
Technical Blog
Build your own lightsaber (sounds) - Part 3
10 years ago
by
D720741534
In the last section, we set up a GPIO pin with a Pulse Width Modulation (PWM) mode of a timer to create a simple audio tone, and observing that waveform on an oscilloscope.  We then amplified that tone with a crude single-transistor amplifier.  In this section, we will connect the MircoSD card to the Starter Kit in order to play audio stored in a file and introduce the onboard Digital-to-Analog Converter (DAC) for single-ended and differential modes.  We will also connected a Class D differential amplifier to the output of our DAC.
Drive Digital Audio Files to Your Speaker
Now that we have proven that we can make sounds using only digital outputs, we will re-use our MicroSD card example from chapter 13 and send those files through the onboard DAC to produce audio data that is more analog and requires no filtering for adding an amplification stage.
Download the lightsaber sound profiles from the repo (originally found on
freesound.org
thanks to Joe Barlow) and load them from your computer into your MicroSD card.  These sound files are already in the .wav format, and that format will work for our needs in this chapter.  If you had found sound effects in any other format, such as .mp3, you would need to use the Audacity program as discussed in the last chapter to convert those sounds to .wav format or build a .mp3 software decoder into your firmware solution.
Note that the FF library used by MicroSD library requires all of your filenames to be in the “8.3” naming format, which harkens back to the 1980’s DOS days.  This means that your names must be named with fewer than eight characters, followed by a period, and then three more characters.  If you use longer names or don’t follow this format, your files won’t open.  So I renamed the files from
freesound.org
to a few files called swing0.wav and idle1.wav on my SD card and placed those in the source code repo.  If you still have the voice tracks on the card from before, it’s OK to leave them on there so that you have some voice tracks for experiments.  Then, connect the MicroSD card reader to the Starter Kit as follows:
Starter Kit
MicroSD Card Breakout
3V3                                                               VCC
GND                                                              GND
PC2 – US2_TX, Location 0                          DI
PC3 – US2_RX, Location 0                          DO
PC4 – US2_CLK, location 0                         SCK
PC5 – US2_CS, Location 0                          CS
To ensure that the MicroSD card is working, load the sound_effects_player.c file, set a breakpoint in dac_helpers.c in the open_file function, and verify that you see “RIFF” in the wavHeader variable.  This ensures that all of those connections are correct and at least the first file it is looking for is on the card.  If you get stopped on a DEBUG_BREAK command, fix the hardware connections and try again.
Connect the Onboard Differential DAC
Some of the EFM32 family, including our Wonder Gecko on the Starter Kit, contain an onboard DAC peripheral and integrated Op Amp that can be used to play audio.  The DAC is 12 bits, which is a little bit shy of what is considered to be high quality audio, but certainly good enough for our sound effects purposes.  But the nice part about the DAC is that it can output an analog voltage, i.e. a voltage that is not limited to either 0 V or 3.3 V, and it can drive to any of 212 values in-between those two voltages, or 4096 steps.  This means that an audio filter design is not required on the MCU outputs, and we can send the data from a DAC directly to any ordinary amplifier circuit.
The DAC is differential, which means that it can double the effective volume of your sounds by driving the opposite voltage to each pin of the speaker.  Before, we simply connected one pin of the speaker to ground, but now we will connect and drive the two pins of the speaker to 0 V or 3.3 V on every edge, resulting in up to 6.6 V of total signal swing as seen by the speaker.
Note that sometimes in embedded development you need more MCU pins than are available.  If this is the case and you need to save pins, you could use the Single Ended mode of the DAC and then send the single-ended signal through an inverter circuit.  Just be sure to send both speaker inputs through the same circuit so that the signal timing will be identical.  In addition, the way that you interpret and feed data to the DAC registers changes depending on single-ended or differential mode, so you will have to take that into account.  This is beyond the scope of this chapter and left as an exercise for the reader.
The integrated Op Amp in the EFM32 is automatically used by the DAC and does not need to be configured separately, other than for configuring and routing the DAC output pins to alternate locations.  For differential mode DAC, two of the integrated Op Amps will be used, leaving one of them free for other purposes.  The Op Amp is still limited to 20 mA of GPIO pin drive and 3.3 V power supply voltage.  It cannot add any more power to the circuit than those limitations.
Connect the TI TPA2005D1 Class D Amplifier
The volume from the integrated differential DAC good for sending to other onboard audio devices, but perhaps it is not loud enough to drive the volume that you want on our little speaker.  Since the DAC is driving an analog voltage, it is easy to amplify using many types of available amplifiers.  Keep in mind that all amplifiers are limited to the source voltage and current requirements on your board.  In our case, we can use either 3.3 V or 5 V on the Starter Kit, and up to 500 mA of power from the USB port on the connected computer.  If we exceed 500 mA of power draw through our amplifier, the connected computer will likely shut down the USB port to avoid damage.
Each GPIO on the Wonder Gecko is capable to drive 20 mA at 3.3 V on our Starter Kit.  By driving differentially from two pins, this looks like 20 mA over 6.6 V to our speaker, or 132 mW of power.   If we want to drive higher than that, we can turn to a differential amplifier, such as the TI TPA2005D1.
The TPA2005D1 is listed as a 1.4 W differential Class D mono amplifier.  It can operate at up to 6 V, which means that it is able to drive up to 12 V of differential voltage to our speaker, while delivering up to 242 mA of current per output pin.
The gain of the audio amplifier is set by that are in series with the signal that is coming from our DAC.  The gain is the amount of amplification factor that you wish to achieve with your amplifier.  The recommended setting is a gain of two, which should double your volume, and it is set by choosing 150 kΩ resistors for those two series resistors RI with the following equation:
If you set these resistors to something too low, for example 0 Ω resistors, the amp will protect itself by going into shutdown mode.  Start with 150 kΩ and then go from there.  I have successfully run it at a gain of six by using 50 kΩ resistors.
The gain resistors on the TPA2005D1 are set to 50 kΩ with a pair of surface-mount 0603 size resistors just to the left of the input pins.  If you would like to change the gain, you must remove those resistors or calculate the parallel resistance by adding new through-hole resistors over top the surface-mounted 0603 resistors.  For example, by adding two new 150 kΩ resistors on top of the surface-mounted 0603 resistors, you will drop the resistance to 75 kΩ for a gain of four.  Just be sure to use 1% resistors and apply the gain to both inputs because the differential amplifier works best if things are evenly matched.
The SHUTDOWN pin on the TPA2005D1 is important to designs that wish to save power.  When the amplifier is enabled (SHUTDOWN pin is high), the amplifier will be consuming up to 4.5 mA of power.  But with the amplifier disabled (SHUTDOWN pin is low), the amplifier will only consume up to 50 µA of power.
Connect the TPA2005D1 as follows:
Starter Kit
TPA2005D1 Card Breakout
Speaker
5V                                                                 PWR +
GND                                                              PWR -
3V3                                                               S (the shutdown pin)
PB11                                                             IN +
PB12                                                             IN -
OUT +                                                           Speaker +
OUT -                                                            Speaker –
Note that the polarities of PB11/PB12 and Speaker +/- are not important.  All that matters is that you have two wires from PB11 and PB12 going to IN + and IN – on the TPA2005D1 and two wires going from OUT + and OUT – going to the two likely unlabeled pins on your plastic speaker.
In the next section, we will learn how to configure the differential DAC on the EFM32 to play higher quality audio from a MicroSD card.
```

## Part 4 — software: DMA ping-pong buffers, DAC setup, sample-rate timer (PRS)

URL: https://community.silabs.com/s/share/a5U1M000000knjvUAA/build-your-own-lightsaber-sounds-part-4 — retrieved **live** 2026-10-01 via `&_escaped_fragment_=` prerender.

### Verbatim transcription


```
Technical Blog
Build your own lightsaber (sounds)! - Part 4
10 years ago
by
D720741534
In the last section, we connected the EFM32 DAC to an amplifier and speaker and connected the MicroSD card.  In this section, we will fetch the sounds from the MicroSD card and send those sounds to the speaker.
MicroSD and DAC Software
When possible, I like to begin with an example application and then modify it for my needs, at least for the prototyping stage.  In chapter 13, I used the DK3850 wavplayer example to create sound effects with an I2S audio chip.  I had to remove some Board Support Package (BSP) files from the example in order to get the wavplayer.c file to run on my Wonder Gecko, because  the example was taken from a different kit.  Once I did that, I found a few errors here or there that I had to correct before I could play sound files from a MicroSD card.  Once I got the example working, only then did I start making changes to add my own functionality.  I also used a local Git revision control system so that I could see differences between file revisions, and I could always roll back the changes if I broke something that was once working.  I recommend revision control systems even for experimentation purposes because one small change in a file can be hard to find later.
For this chapter, I started with the code from chapter 13.  I tell you this so that you can take a look at the differences between those files and see how things have evolved.
The first thing that I did was comment out all support for I2S.  I didn’t want to look at those blocks of code in my new DAC implementation anymore.  I left them around for a while so that I could refer to them as I made the switch to the DAC, and then deleted away most of those lines entirely once the DAC was working.  My main function in sound_effects_player.c is very simple:
#include "em_device.h"
#include "em_chip.h"
#include "em_cmu.h"
#include "em_timer.h"
#include "em_gpio.h"
#include "dac_helpers.h"
#include "utilities.h"
int main(void)
{
CHIP_Init();
// Need to boost the clock to get above 7kHz
CMU_ClockSelectSet(cmuClock_HF, cmuSelect_HFXO);
CMU_ClockEnable(cmuClock_GPIO, true);
CMU_ClockEnable(cmuClock_DMA, true);
// Enable GPIO output for Timer3, Compare Channel 2 on PE2
GPIO_PinModeSet(gpioPortE, 2, gpioModePushPull, 0);
// Show the sample rate on PE1 for debug
GPIO_PinModeSet(gpioPortE, 1, gpioModePushPull, 0);
// Get the systick running for delay() functions
if (SysTick_Config(CMU_ClockFreqGet(cmuClock_CORE) / 1000))
{
DEBUG_BREAK;
}
play_sound(TEST_SOUND4);
DAC_setup();
DAC_TIMER_setup();
while (1) ;
}
Most of the work is done in the play_sound function, located in dac_helpers.c, which opens the file sent in, which in this case is “sweet4.wav” defined as TEST_SOUND4.  The play_sound function loads two ping pong RAM buffers and initializes the DMA transfers.  We have to call play_sound before we set up the DAC and sample rate timer because the timing of the DAC depends on the sample rate, and we don’t know that until after we have read the sample rate from the sound file.  The DAC is then setup to work with DMA, and a timer is used to tell the DAC when to start the digital-to-analog conversion according to the sample rate contained in the .wav file header.
The dac_helper.c file contains all of the code necessary to open files, load data from those files into two ping pong DMA buffers, setup the sample rate timer, setup the DAC, setup the DMA, and handle the callback from DMA cycle completion.  The following figure is a graphical overview of how the code works.
The code starts by mounting the MicroSD card and ensuring that there is a file system, and then opening the named file.  If any of those steps fail, the debugger will break in at several DEBUG_BREAK lines to show you where things went wrong.  If you are
not
connected to the debugger (Simplicity Studio IDE), then your firmware will just halt and refuse to function. Therefore, DEBUG_BREAK statements are only for experimenting and learning using the IDE.
The open_file function has a static variable that remembers if the MircoSD card has been initialized, and if it has not been initialized yet, then it calls on prepare_microsd_card to do the one-time initialization and mounts the FAT file system.
void prepare_microsd_card()
{
/* Initialize filesystem */
MICROSD_Init();
FRESULT res = f_mount(0, &Fatfs);
if (res != FR_OK)
{
/* No micro-SD with FAT32 is present */
DEBUG_BREAK
}
}
void open_file(char * filename)
{
static bool first_time = true;
if (first_time)
{
prepare_microsd_card();
}
/* Open wav file from SD-card */
if (f_open(&WAVfile, filename, FA_READ) != FR_OK)
{
/* No micro-SD with FAT32, or no WAV_FILENAME found */
DEBUG_BREAK
}
ByteCounter = 0;
/* Read header and place in header struct */
f_read(&WAVfile, &wavHeader, sizeof(wavHeader), &bytes_read);
if (first_time)
{
/* Fill both primary and alternate RAM-buffer before start */
FillBufferFromSDcard( wavHeader.channels, true);
FillBufferFromSDcard( wavHeader.channels, false);
first_time = false;
}
}
When the file is first opened, the ping pong buffers in RAM are filled with the first bits of data.  The FillBufferFromSDcard function sets up DMA after the RAM buffers are loaded, but DMA is waiting for a DMA request from the DAC to do anything.  As soon as the DAC is configured and started, it will begin to request data from DMA using the DMA request when the sample rate timer indicates it is time for a new sample over the Peripheral Reflex System (PRS).  Therefore, the TIMER drives the DAC, which drives the DMA and everything else.  This is exactly as it should be since sound is based on a sample rate.
Note that two “ping pong” buffers are used so that the DMA engine can be working on one buffer while the other buffer is loaded from the MicroSD card.  The two buffers are used for both mono and stereo files, and both buffers are used for left and right channels or just a single mono channel.  Stereo data is always interlaced in .wav files, and I have modified the stereo logic of the FillBufferFromSDcard to properly blend sound from the left and right channels into a single channel for our mono DAC:
if (channels > 1)
{
/* Stereo, Store Left and Right data interlaced as in wavfile */
/* DMA is writing the data to the combined register as interlaced data*/
/* First buffer is filled from SD-card */
f_read(&WAVfile, buffer, 4 * BUFFERSIZE, &bytes_read);
ByteCounter += bytes_read;
for (i = 0; i < 2 * BUFFERSIZE; i++)
{
if (!(i & 1))
{
tmp = 0;
}
tmp += buffer[i];
if (i & 1)
{
/* Convert to 12 bits */
tmp >>= 4;
buffer[i-1] = tmp;
buffer[i] = tmp;
}
}
}
In this code, the buffer points to one of the two global ping pong buffers that is not currently in use by the DMA engine.  It then fills that buffer with data from the MicroSD card with the f_read function and keeps track of how much data has been processed with the ByteCounter variable.  But more processing is needed after that.  For stereo .wav files, we have to convert the samples from two stereo channels into a single mono channel for our mono DAC.  This is accomplished by reading two samples, adding them together, and then placing the blended samples back into the left and right channels in the buffer.  This code was originally used on I2S chip that was capable of stereo, which is why we have the extra space allocated for stereo sound in the buffer.  We are still not done.  The samples in .wav files are 16-bits and we only have a 12-bit DAC, so we do a right shift by four bits to create a 12-bit sample, and then place this modified sample back into the global RAM ping pong buffer.
After the RAM buffers are ready to go, a DMA transfer is initiated.  The DMA engine waits for the DAC to tell it when to give it another sample, and the DAC waits for the sample rate TIMER0 (named DAC_TIMER in the code) to tell it when to process each sample.  This whole conversion process starts at the TIMER0, which is the heartbeat that controls the overall cadence of all audio conversion.  The DAC processes data on that heartbeat and is fed by the DMA engine at the exact moment it requires 32 bits of data to perform the next digital-to-audio conversion of a single audio sample.  When the DMA engine processes all of the data in one of the ping pong buffers, it triggers a Ping Pong Complete callback to a function that loads more data from the MicroSD card, processing the data on the fly to blend samples, adjusting for 12-bit DAC, etc., right as it loads the RAM buffers.  This whole process repeats until the number of bytes processed by FillBufferFromSDcard is equal to the number of bytes in the source file, and then the DMA engine is told to stop.  At that point, the TIMER0 continues to run, but the DAC doesn’t do anything because it never receives any data from the DMA engine.
The DAC in this implementation is set up to operate in differential mode, which allows 2’s complement signed data from normal .wav files to be placed into the DAC registers for conversion.  The DAC automatically configures the integrated Op Amps for the primary differential outputs.  During the function, we also set up the PRS system to watch for a signal from the DAC timer and request new samples from the DMA engine.
void DAC_setup(void)
{
CMU_ClockEnable(cmuClock_DAC0, true);
CMU_ClockEnable(cmuClock_PRS, true);
DAC_Init_TypeDef        init        = DAC_INIT_DEFAULT;
DAC_InitChannel_TypeDef initChannel = DAC_INITCHANNEL_DEFAULT;
/* Calculate the DAC clock prescaler value that will result in a DAC clock
* close to 1 MHz. Second parameter is zero, if the HFPERCLK value is 0, the
* function will check what the HFPERCLK actually is. */
init.prescale = DAC_PrescaleCalc(1000000, 0);
// Differential mode
init.diff = true;
// Higher reference of 3.3V
init.reference = dacRefVDD;
/* Initialize the DAC. */
DAC_Init(DAC0, &init);
/* Enable prs to trigger samples at the right time with the timer */
initChannel.prsEnable = true;
initChannel.prsSel    = dacPRSSELCh0;
/* Both channels can be configured the same
* and be triggered by the same prs-signal. */
DAC_InitChannel(DAC0, &initChannel, 0);
DAC_InitChannel(DAC0, &initChannel, 1);
DAC_Enable(DAC0, 0, true);
DAC_Enable(DAC0, 1, true);
// By default, DAC is output to PB11 and PB12.  Use following code to move to alt pins if necessary
//  DAC0->OPA0MUX |= DAC_OPA0MUX_OUTMODE_ALT | DAC_OPA0MUX_OUTPEN_OUT4;
//  DAC0->OPA1MUX |= DAC_OPA1MUX_OUTMODE_ALT | DAC_OPA1MUX_OUTPEN_OUT4;
}
TIMER0 is likewise set up to trigger a PRS signal according to the sample rate contained in the sound file, and it is set up to use the same PRS channel that the DAC is waiting on to start digital-to-analog conversion.
void DAC_TIMER_setup(void)
{
CMU_ClockEnable(cmuClock_TIMER0, true);
uint32_t timerTopValue;
/* Use default timer configuration, overflow on counter top and start counting
* from 0 again. */
TIMER_Init_TypeDef timerInit = TIMER_INIT_DEFAULT;
TIMER_Init(TIMER0, &timerInit);
/* PRS setup */
/* Select TIMER0 as source and TIMER0OF (Timer0 overflow) as signal (rising edge) */
PRS_SourceSignalSet(0, PRS_CH_CTRL_SOURCESEL_TIMER0, PRS_CH_CTRL_SIGSEL_TIMER0OF, prsEdgePos);
/* Calculate the proper overflow value */
timerTopValue = CMU_ClockFreqGet(cmuClock_TIMER0) / wavHeader.frequency;
/* Write new topValue */
TIMER_TopBufSet(TIMER0, timerTopValue);
}
The configuration and initialization of the DMA transfers were detailed in chapter 13 and the only change in this chapter is to change the target and data increment size from USART, which was necessary for the I2S chip, to the DAC, the new target.  The USART allowed two 32-bit words to be transferred at one time, while the DAC only allows one 32-bit word to be transferred at one time.
In the next section, we will add some sound blending to the audio playback algorithm to ensure that multiple sound effects can play at the same time.  We will then connect the accelerometer from chapter 10 to induce some motion-activated sounds.
```

## Part 5 — runtime sample blending plus ADXL345 motion triggering

URL: https://community.silabs.com/s/share/a5U1M000000knzNUAQ/build-your-own-lightsaber-sounds-part-5 — retrieved **live** 2026-10-01 via `&_escaped_fragment_=` prerender.

### Verbatim transcription


```
Technical Blog
Build Your own Lightsaber (Sounds)! - Part 5
10 years ago
by
D720741534
In the last section, we configured the internal DAC to play audio from the MicroSD card.  In this section, we will develop the ability to blend multiple sound effects on the fly and then use that new capability to play the appropriate sound whenever an accelerometer sees activity.
Blending Sound Samples on the Fly
In order to create a lightsaber sound effects generator, we need to always play a primary sound that handles the idle “humming” sound and then other sounds such as swings and clashes that play on top of the first sound.  Since we don’t know when these things are going to happen ahead of time, we can’t simply create static sound effects that cover all possible combination of sound events.  We have to dynamically blend sounds together at run time.  This code is started in lightsaber_effects_player.c, while most of the work once again is performed in dac_helpers.c.
If we had infinite time and infinite RAM, our lives as embedded developers would be so much easier.  Our resources are limited, but they are perfectly capable to do the job.  It just requires us to think a little bit differently.  We have to be able to keep data flowing from multiple sources to “feed” the MCU with new audio data before the next sample is due at the inputs to the speaker.  This requires more complicated code than simply reading the entire contents of sound file A, then read from sound file B, and then mixing them to create sound file C, just sitting there waiting for its data to be accessed.  Instead, we have to do all of this source data fetching and mixing on the fly, just before the speaker needs a new sample.  This is more efficiently accomplished with the DMA engine, which will free your core up to do other things or allow the MCU drop into a lower power state to save energy.
Surprisingly, the only thing that needs to be done to blend sounds is to add two samples from different files together (or different channels, in the case of stereo-to-mono conversion.)  There is no averaging that needs to be done or anything like that, and it sounds very naturally blended.  Most of the audio data is contained in the lower quarter of the possible digital range, with peaks that will occasionally jump above the three-quarter mark.  The adding of sounds together works because the peaks in the audio files don’t usually happen at the same exact moment in time.  When they do, you will have some clipping, but it should work for simple sound effects used in this example.
In order to blend more than a single sound effect, we fetch data from multiple files on the SD card and do the blending right as we load the RAM buffers, keeping separate file pointers and byte counts for each file read.  In order to re-use the same example from before and keep thing simple, I have chosen to create a new main file called lightsaber_effects_player.c and create a new add_track function in dac_helpers.c that can only add sounds to the currently-playing track.  It would be a better solution to rewrite the whole dac_helper.c to handle any number of sounds to be played at any time, but I was trying to show the difference between playing a single sound and playing multiple sounds to be clear in what was changing.  I have marked the places in dac_helper.c where extra code was added to play multiple blended sounds so that you can clearly see that code.
Here is the add_track function, plus an array of new data structures that I created to handle the dynamically added tracks.
typedef struct
{
FIL file_object;
uint32_t total_bytes;
uint32_t bytes_processed;
} additional_tracks_struct;
static additional_tracks_struct additional_tracks[MAX_TRACKS];
static uint8_t num_of_additional_tracks = 0;
void add_track(char * filename)
{
// If we have exceeded the number of avail tracks, return
if (num_of_additional_tracks >= MAX_TRACKS) return;
// Find an available track
uint8_t track_num = 0;
while (track_num < MAX_TRACKS)
{
if (additional_tracks[track_num].file_object.fs == 0)
{
break;
}
track_num++;
}
if (track_num >= MAX_TRACKS) return;
/* Open wav file from SD-card */
if (f_open(&additional_tracks[track_num].file_object, filename, FA_READ) != FR_OK)
{
/* No micro-SD with FAT32, or no WAV_FILENAME found */
DEBUG_BREAK
}
/* Read header and place in header struct */
WAV_Header_TypeDef tmp_header;
UINT bytes_read;
f_read(&additional_tracks[track_num].file_object, &tmp_header, sizeof(tmp_header), &bytes_read);
additional_tracks[track_num].total_bytes = tmp_header.bytes_in_data;
num_of_additional_tracks++;
}
Notice that the add_track does not call on prepare_microsd_card as it relies on the play_sound function to have already done that.
Inside of the FillBufferFromSDcard function, I loop over the additional_tracks array looking for additional sounds to fetch from the MicroSD card:
/* Read data into temporary buffer. */
if (WAVfile.fs != 0)
{
f_read(&WAVfile, ramBufferTemporaryMono, BUFFERSIZE*2, &bytes_read);
ByteCounter += bytes_read;
}
/////////////////////////////////////////////////////////////////////////////////////
// This part added for lightsaber_effects_player.c
uint8_t track_num = 0;
int16_t tmp_buffer[MAX_TRACKS][BUFFERSIZE*2];
while (track_num < num_of_additional_tracks)
{
if (additional_tracks[track_num].total_bytes > 0)
{
// Add more sounds to ramBufferTemporaryMono as necessary
f_read(&additional_tracks[track_num].file_object, tmp_buffer[track_num],
BUFFERSIZE*2, &bytes_read);
additional_tracks[track_num].bytes_processed += bytes_read;
}
track_num++;
}
/////////////////////////////////////////////////////////////////////////////////////
The first call to f_read is using the initial sound played, addressed with the global WAVfile file pointer.  Any additional sounds are read into a new tmp_buffer for each new track found to contain a non-zero file pointer in the additional_tracks array.  The bytes read by the f_read function are each stored separately for each new sound effect to be blended.
After all of the sound effects are loaded into buffers, the data is added to the single sample being processed before it is converted to 12-bit data and stored back in the RAM buffer for later retrieval by the DMA engine:
j = 0;
for (i = 0; i < (2 * BUFFERSIZE) - 1; i += 2)
{
tmp = ramBufferTemporaryMono[j];
/////////////////////////////////////////////////////////////////////////////////////
// This part added for lightsaber_effects_player.c
track_num = 0;
while (track_num < num_of_additional_tracks)
{
if (additional_tracks[track_num].total_bytes > 0)
{
// Add more sounds as necessary
tmp += tmp_buffer[track_num][j];
}
track_num++;
}
///////////////////////////////////////////////////////////////////////////////////
/* Convert to 12 bits */
tmp >>= 4;
buffer[ i     ] = tmp;
buffer[ i + 1 ] = tmp;
j++;
Finally, in the DMA callback PingPongTransferComplete function, the additional_tracks array is searched for sounds that are still playing, which would keep the DMA engine active even if the primary sound effect has stopped playing.  Any sounds that have stopped are closed so that the file pointers are set back to zero.
/* Stop DMA if bytecounter is equal to datasize or larger */
bool stop = true;
if (ByteCounter >= wavHeader.bytes_in_data)
{
f_close(&WAVfile);
}
else
{
stop = false;
}
/////////////////////////////////////////////////////////////////////////////////////
// This part added for lightsaber_effects_player.c
for (int track_num=0; track_num < MAX_TRACKS; track_num++)
{
if (additional_tracks[track_num].total_bytes > 0)
{
if (additional_tracks[track_num].bytes_processed >=
additional_tracks[track_num].total_bytes)
{
f_close(&additional_tracks[track_num].file_object);
additional_tracks[track_num].bytes_processed = 0;
additional_tracks[track_num].total_bytes = 0;
num_of_additional_tracks--;
}
else
{
stop = false;
}
}
}
/////////////////////////////////////////////////////////////////////////////////////
Connect the Accelerometer for Motion Triggered Sound Effects
Now that we have sound effects engine that can blend the lightsaber hum with the lightsaber swing sounds, all on the fly, we can connect this to our ADXL345 accelerometer from chapter 10 and play those sounds at the appropriate time, based on the accelerometer event.  This will create a motion-activated sound effects generator.
The simplest case for prototyping is to configure the ADXL345 for motion-activated interrupts and then poll the interrupt register for the required interrupt.  But the accelerometer also outputs those interrupts to pins that will allow your MCU to go into a low power state until that interesting event happens.   The interrupt pins should be used for a production solution.
Connect the ADXL345 to the MCU as follows:
Starter Kit
ADXL345 Card Breakout
VMCU (since we are out of 3V3 pins)      VIN  Be careful, DO NOT connect to 3V3 here
GND                                                             GND
SDA                                                              PD6
SCL                                                               PD7
Once connected, I reused some of the setup files from chapter 10 and made sure that communication was established to the ADXL345 by checking that the ADXL345_REG_DEVID register on the ADXL345 could be read from the device.  I then configured the ADXL345 interrupts for our purposes to set them up like so in adxl.c:
void i2c_init_registers()
{
// Set range to 16g and FULL resolution
i2c_write_register(ADXL345_REG_DATA_FORMAT, ADXL345_RANGE_16_G );
// ADXL_BW_RATE = 50 hz, limited by I2C speed
i2c_write_register(ADXL345_REG_BW_RATE, ADXL345_DATARATE_50_HZ);
// Set up threshold for activity, found through trial and error
i2c_write_register(ADXL345_REG_THRESH_ACT, 0x90);
// Turn on the axes that will participate
i2c_write_register(ADXL345_REG_ACT_INACT_CTL, ADXL345_ACT_ac_dc | ADXL345_ACT_X | ADXL345_ACT_Y | ADXL345_ACT_Z);
// Set up interrupt outputs, sent to INT1 pin by default
i2c_write_register(ADXL345_REG_INT_ENABLE, ADXL345_INT_Activity );
//uint32_t value = i2c_read_register(ADXL345_REG_INT_ENABLE);
// Clear interrupts by reading the INT_SOURCE register
i2c_read_register(ADXL345_REG_INT_SOURCE);
// Start measurement
i2c_write_register(ADXL345_REG_POWER_CTL, 0x08);
}
This is all called from main in lightsaber_effects_player.c, which follows:
#include "em_device.h"
#include "em_chip.h"
#include "em_cmu.h"
#include "em_timer.h"
#include "em_gpio.h"
#include "dac_helpers.h"
#include "utilities.h"
#include <stdlib.h>
#include "adxl.h"
#include "utilities.h"
int main(void)
{
CHIP_Init();
// Need to boost the clock to get above 7kHz
CMU_ClockSelectSet(cmuClock_HF, cmuSelect_HFXO);
CMU_ClockEnable(cmuClock_GPIO, true);
CMU_ClockEnable(cmuClock_DMA, true);
// Enable GPIO output for Timer3, Compare Channel 2 on PE2
GPIO_PinModeSet(gpioPortE, 2, gpioModePushPull, 0);
// Show the sample rate on PE1 for debug
GPIO_PinModeSet(gpioPortE, 1, gpioModePushPull, 0);
// Get the systick running for delay() functions
if (SysTick_Config(CMU_ClockFreqGet(cmuClock_CORE) / 1000))
{
DEBUG_BREAK;
}
i2c_setup();
// Offset zero is Device ID
uint16_t value = i2c_read_register(ADXL345_REG_DEVID);
if (value != DEVICE_ID)
{
DEBUG_BREAK
}
i2c_init_registers();
// We need to start the sound, which starts DMA, before we set up the DAC_TIMER
play_sound(SABER_IDLE);
DAC_setup();
DAC_TIMER_setup();
uint32_t adxl_debounce_timer = set_timeout_ms(500);
while (1)
{
play_sound(SABER_IDLE);
// Clear interrupts by reading the INT_SOURCE register
uint8_t int_source = i2c_read_register(ADXL345_REG_INT_SOURCE);
if (int_source & ADXL345_INT_Activity)
{
// Clear interrupts by reading the INT_SOURCE register
i2c_read_register(ADXL345_REG_INT_SOURCE);
if (expired_ms(adxl_debounce_timer))
{
add_track(SABER_SWING);
adxl_debounce_timer = set_timeout_ms(500);
}
}
}
}
At idle, we get a nice crackle hum sound.  When you tap the ADXL345 breakout board, you should hear a swing sound blended with the hum sound.  You could add additional reads of the ADXL345 acceleration registers described in chapter 10 to determine if the motion was a swing or an impact and load up the appropriate sound at that point.  I leave that as an exercise for the reader.  Give it a try, and use the force, Luke!
This wraps up our foray into sound.  I hope that you have an easier time creating loud and clear sound than what I had to go through to get to this point.  Don’t get too intimidated by the vast amount of information on this topic that you will find on the Internet.
```

---

## Gaps in the above text (images on the page)

The articles embed several equations and circuit diagrams as PNGs. The sentences are transcribed; the images are not. Specifically:

| Location | What is an image | Status |
|---|---|---|
| Part 1, para beginning "The EFM32 can drive up to 20 mA" | an inline figure whose alt/caption text got flattened into the sentence as "The EFM32 can drive up to 20 mA on a single GPIO pin" | caption transcribed, figure **[reconstructed — not verbatim]** |
| Part 1 | banner image `14_title.png` | could not retrieve (image) |
| Part 2 | the PWM test circuit schematic | could not retrieve (image) |
| Part 2 | "The cutoff frequency of the RC filter is" — **the equation is an image** | **[reconstructed — not verbatim]**: the standard first-order RC low-pass cutoff `Fcutoff = 1 / (2*pi*R*C)`. With R = 10 kOhm and Fcutoff = 7 kHz the article's own worked result is C ~= 2 nF (0.002 uF), which I verified numerically: C = 1/(2*pi*10e3*7e3) = 2.27e-9 F. |
| Part 2 | "Rearranging, and setting Fcutoff to 7 kHz, we can find the value of C:" — **the solved equation is an image** | **[reconstructed — not verbatim]**: `C = 1 / (2*pi*Fcutoff*R)` |
| Part 2 | oscilloscope screenshots of the PWM waveform and the filtered sine | could not retrieve (images) |
| Part 3 | "the recommended setting is a gain of two ... it is set by choosing 150 kOhm resistors for those two series resistors RI with the following equation:" — **the TPA2005D1 gain equation is an image** | **could not retrieve.** I will not guess it. |
| Part 4 | the graphical overview diagram of the file/DMA/DAC data flow | could not retrieve (image) |
| Part 5 | ADXL345 pinout / board photo if present | could not retrieve (image) |

## Constants that appear only in the companion source, not in the article prose

Sourced from `github.com/bfricker/makersguide`, `14_lightsaber_sounds/src/dac_helpers.c` and `.h`. These are **[reconstructed from source, not verbatim from the blog]**:

```c
#define TEST_SOUND4  "sweet4.wav"
#define TEST_SOUND5  "sweet5.wav"
#define SABER_IDLE   "idle1.wav"
#define SABER_SWING  "swing0.wav"

/* BUFFERSIZE should be between 512 and 1024, depending on available ram on efm32 */
#define BUFFERSIZE   512
#define MAX_TRACKS   3

static int16_t ramBufferTemporaryMono[BUFFERSIZE*2];
static int16_t ramBufferDacData0Stereo[2 * BUFFERSIZE];
static int16_t ramBufferDacData1Stereo[2 * BUFFERSIZE];
static additional_tracks_struct additional_tracks[MAX_TRACKS];
```

So: 512-sample ping-pong buffers (each sized 2*BUFFERSIZE int16 to hold interleaved L+R), up to 3 concurrently blended extra tracks on top of the primary track, and `tmp_buffer[MAX_TRACKS][BUFFERSIZE*2]` as the per-track read scratch.

## Hardware part numbers and part links named in the articles

- Silicon Labs **EFM32 Wonder Gecko** Starter Kit (EFM32WG), the DAC in question is **DAC0**, a **12-bit** differential DAC with an integrated op amp pair.
- **ADXL345** accelerometer breakout (Digi-Key `1568-1035-ND` search link) — I2C, `ADXL345_REG_DEVID`, range 16 G, 50 Hz data rate, activity interrupt on INT1.
- **Micro SD card breakout** (SparkFun product 544), Micro SD card + SD adapter (SparkFun 11044 link in Part 1's list; Part 1 lists product 544 twice for the Micro SD breakout and Micro SD memory card breakout).
- **TI TPA2005D1** Class D differential mono amplifier breakout (SparkFun product 11044), 1.4 W, up to 6 V, 242 mA per output pin, 50 kOhm 0603 factory gain resistors, SHUTDOWN pin high = up to 4.5 mA, low = up to 50 uA.
- **CS4344** I2S DAC used in the preceding chapter 13 (DK3850 / PmodI2S), only referenced, not used in this series.
- Speaker referenced as a typical **8 Ohm** unit; the MCU-side budget quoted is 20 mA at 3.3 V per GPIO, 132 mW differentially into 6.6 V swing, against a 500 mA USB budget.

## Could not retrieve

1. **Parts 6 and later** — do not appear to exist. Exhausted: Wayback CDX domain crawl of `community.silabs.com` (nine lightsaber URLs total, Parts 1-5 new scheme + Parts 1-3 legacy), the migrated blog index, and web search.
2. **The TPA2005D1 gain-setting equation** in Part 3 — rendered as an image.
3. **All circuit-diagram and oscilloscope-screenshot images** across the series.
4. **The literal `github.com/SiliconLabs/makersguide` URL** is dead (404). The mirror `github.com/bfricker/makersguide` works and is what I read for the constants above; I could not confirm from the page whether they are identical, though the file names and code match the article text exactly.
5. **Publication dates for Parts 1, 4, 5** — the migrated pages say only "10 years ago". Parts 2 and 3 carry explicit 2016-11-18 and 2016-11-29 timestamps on the legacy originals, so the whole series is November 2016.
